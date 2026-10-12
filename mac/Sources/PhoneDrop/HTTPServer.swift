import Foundation
import Network

/// A parsed HTTP request; the body is read completely before the handler runs.
/// TR: Ayrıştırılmış HTTP isteği; gövde, işleyici çalışmadan önce tamamen okunur.
struct HTTPRequest {
    let method: String
    let target: String
    let path: String
    let query: [String: String]
    let headers: [String: String]
    var body = Data()
}

struct HTTPResponse {
    var status: Int
    var contentType = "application/octet-stream"
    var body = Data()
    /// For streamed responses: total length and a producer that writes it.
    /// TR: Akış halindeki yanıtlar için: toplam uzunluk ve gövdeyi yazan üretici.
    var streamLength: Int64? = nil
    var stream: ((StreamWriter) -> Void)? = nil

    static func json(_ status: Int, _ obj: [String: Any]) -> HTTPResponse {
        let data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
        return HTTPResponse(status: status, contentType: "application/json", body: data)
    }

    static func error(_ status: Int, _ msg: String) -> HTTPResponse { json(status, ["error": msg]) }
}

/// Lets a handler push a streamed body with backpressure.
/// TR: Bir işleyicinin akış halindeki gövdeyi geri basınçla (backpressure) göndermesini sağlar.
final class StreamWriter {
    fileprivate let conn: NWConnection
    fileprivate let done: (Bool) -> Void

    fileprivate init(conn: NWConnection, done: @escaping (Bool) -> Void) {
        self.conn = conn
        self.done = done
    }

    func write(_ data: Data, completion: @escaping (Bool) -> Void) {
        conn.send(content: data, completion: .contentProcessed { err in completion(err == nil) })
    }

    func finish(ok: Bool) { done(ok) }
}

protocol HTTPHandler: AnyObject {
    /// Called on a background queue; must call `respond` exactly once.
    /// TR: Arka plan kuyruğunda çağrılır; `respond` tam olarak bir kez çağrılmalıdır.
    func handle(_ req: HTTPRequest, respond: @escaping (HTTPResponse) -> Void)
    /// Upload progress while a request body is still arriving (delta may be negative on abort).
    /// TR: İstek gövdesi gelirken yükleme ilerlemesi (iptalde delta negatif olabilir).
    func bodyProgress(_ head: HTTPRequest, delta: Int)
}

/// A small HTTP/1.1 server on Network.framework: one listener, keep-alive connections, and Bonjour
/// advertising. Uploads arrive in chunks, so reading each body into memory stays cheap.
/// TR: Network.framework üzerinde küçük bir HTTP/1.1 sunucusu: tek dinleyici, açık tutulan bağlantılar ve
/// Bonjour duyurusu. Yüklemeler parçalar halinde geldiği için her gövdeyi belleğe okumak ucuz kalır.
final class HTTPServer {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "phonedrop.http")
    weak var handler: HTTPHandler?
    private(set) var port: UInt16 = 0
    var onReady: ((UInt16) -> Void)?

    static let maxBody = 70 * 1024 * 1024

    func start(preferredPort: UInt16, service: NWListener.Service) {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
        }
        let l = (try? NWListener(using: params, on: NWEndpoint.Port(rawValue: preferredPort)!))
            ?? (try! NWListener(using: params))
        l.service = service
        l.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.port = l.port?.rawValue ?? 0
                self.onReady?(self.port)
            case .failed:
                // Port busy: retry on any free port.
                l.cancel()
                if preferredPort != 0 {
                    self.start(preferredPort: 0, service: service)
                }
            default:
                break
            }
        }
        l.newConnectionHandler = { [weak self] c in
            guard let self else { return }
            HTTPConnection(conn: c, server: self).start()
        }
        listener = l
        l.start(queue: queue)
    }

    func updateService(_ service: NWListener.Service) {
        listener?.service = service
    }
}

/// One client connection: parses requests, reports body progress and writes responses.
/// TR: Tek bir istemci bağlantısı: istekleri ayrıştırır, gövde ilerlemesini bildirir ve yanıtları yazar.
private final class HTTPConnection {
    let conn: NWConnection
    unowned let server: HTTPServer
    private let q = DispatchQueue(label: "phonedrop.conn")
    private var buf = Data()
    private var counted = 0
    private var current: HTTPRequest?
    private var selfRef: HTTPConnection?

    init(conn: NWConnection, server: HTTPServer) {
        self.conn = conn
        self.server = server
    }

    func start() {
        selfRef = self
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.closed()
            default: break
            }
        }
        conn.start(queue: q)
        readHead()
    }

    private func closed() {
        if let req = current, counted != 0 {
            server.handler?.bodyProgress(req, delta: -counted)
            counted = 0
        }
        current = nil
        selfRef = nil
    }

    private func close() { conn.cancel() }

    private func readHead() {
        if let r = buf.range(of: Data("\r\n\r\n".utf8)) {
            let head = Data(buf[buf.startIndex..<r.lowerBound])
            buf = Data(buf[r.upperBound...])
            parseHead(head)
            return
        }
        if buf.count > 64 * 1024 { close(); return }
        conn.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, done, err in
            guard let self else { return }
            if let data, !data.isEmpty { self.buf.append(data) }
            if err != nil || (done && (data?.isEmpty ?? true)) { self.close(); return }
            self.readHead()
        }
    }

    private func parseHead(_ head: Data) {
        guard let text = String(data: head, encoding: .utf8) else { close(); return }
        let lines = text.components(separatedBy: "\r\n")
        let parts = lines[0].split(separator: " ")
        guard parts.count >= 2 else { close(); return }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let i = line.firstIndex(of: ":") else { continue }
            headers[line[..<i].lowercased()] = line[line.index(after: i)...].trimmingCharacters(in: .whitespaces)
        }
        let target = String(parts[1])
        let comps = URLComponents(string: target)
        var query: [String: String] = [:]
        comps?.queryItems?.forEach { query[$0.name] = $0.value ?? "" }
        var req = HTTPRequest(method: String(parts[0]), target: target, path: comps?.path ?? target,
                              query: query, headers: headers)
        let len = Int(headers["content-length"] ?? "0") ?? -1
        guard len >= 0, len <= HTTPServer.maxBody else {
            send(.error(413, "body too large"), keepAlive: false)
            return
        }
        current = req
        counted = 0
        readBody(len) { [weak self] body in
            guard let self else { return }
            // From here on the handler owns progress accounting for this body
            self.counted = 0
            req.body = body
            let keepAlive = headers["connection"]?.lowercased() != "close"
            guard let handler = self.server.handler else { self.close(); return }
            handler.handle(req) { resp in
                self.q.async {
                    self.current = nil
                    self.counted = 0
                    self.send(resp, keepAlive: keepAlive)
                }
            }
        }
    }

    private func readBody(_ len: Int, then: @escaping (Data) -> Void) {
        if buf.count > 0, let req = current {
            let n = min(buf.count, len)
            counted += n
            server.handler?.bodyProgress(req, delta: n)
        }
        buf.reserveCapacity(len)
        receiveBody(len, then: then)
    }

    private func receiveBody(_ len: Int, then: @escaping (Data) -> Void) {
        if buf.count >= len {
            let body = Data(buf.prefix(len))
            buf = Data(buf.dropFirst(len))
            then(body)
            return
        }
        conn.receive(minimumIncompleteLength: 1, maximumLength: min(len - buf.count, 1 << 20)) { [weak self] data, _, done, err in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buf.append(data)
                self.counted += data.count
                if let req = self.current { self.server.handler?.bodyProgress(req, delta: data.count) }
            }
            if err != nil || (done && self.buf.count < len) { self.close(); return }
            self.receiveBody(len, then: then)
        }
    }

    private static let reasons = [200: "OK", 400: "Bad Request", 403: "Forbidden", 404: "Not Found",
                                  409: "Conflict", 413: "Payload Too Large", 500: "Internal Server Error"]

    private func send(_ resp: HTTPResponse, keepAlive: Bool) {
        let length = resp.streamLength ?? Int64(resp.body.count)
        var head = "HTTP/1.1 \(resp.status) \(Self.reasons[resp.status] ?? "OK")\r\n"
        head += "Content-Type: \(resp.contentType)\r\nContent-Length: \(length)\r\n"
        head += "Cache-Control: no-store\r\nConnection: \(keepAlive ? "keep-alive" : "close")\r\n\r\n"
        let after: (Bool) -> Void = { [weak self] ok in
            guard let self else { return }
            self.q.async { if ok && keepAlive { self.readHead() } else { self.close() } }
        }
        if let stream = resp.stream {
            conn.send(content: Data(head.utf8), completion: .contentProcessed { [weak self] err in
                guard let self else { return }
                if err != nil { self.close(); return }
                stream(StreamWriter(conn: self.conn, done: after))
            })
        } else {
            conn.send(content: Data(head.utf8) + resp.body, completion: .contentProcessed { err in after(err == nil) })
        }
    }
}
