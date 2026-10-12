import CryptoKit
import Foundation

extension NSLocking {
    @discardableResult
    func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}

/// One file coming from the phone.
/// TR: Telefondan gelen tek bir dosya.
final class IncomingTransfer {
    let id: String
    let name: String
    let size: Int64
    let mtime: Int64
    let chunk: Int
    var done: Set<Int>
    /// Subfolder of the download folder chosen on the phone ("" = the download folder itself).
    /// TR: Telefonda seçilen, kayıt klasörünün alt klasörü ("" = kayıt klasörünün kendisi).
    var folder = ""
    var live: Int64 = 0
    var samples: [(Date, Int)] = []
    var updated = Date()

    init(id: String, name: String, size: Int64, mtime: Int64, chunk: Int, done: Set<Int> = []) {
        self.id = id
        self.name = name
        self.size = size
        self.mtime = mtime
        self.chunk = chunk
        self.done = done
    }

    var chunkCount: Int { Int((size + Int64(chunk) - 1) / Int64(chunk)) }

    func chunkLength(_ i: Int) -> Int { Int(min(Int64(chunk), size - Int64(i) * Int64(chunk))) }

    var doneBytes: Int64 { done.reduce(0) { $0 + Int64(chunkLength($1)) } }
}

/// What the UI shows for one transfer in progress, in either direction.
/// TR: Arayüzün, süren tek bir aktarım için (iki yönde de) gösterdiği bilgiler.
struct TransferSnapshot: Identifiable {
    enum Direction { case incoming, outgoing }
    let id: String
    let name: String
    let size: Int64
    let received: Int64
    let speed: Double
    let direction: Direction
}

/// A file on the Mac that is offered to the phone.
/// TR: Mac'te duran ve telefona sunulan bir dosya.
struct OutboxFile: Codable, Identifiable, Hashable {
    let id: String
    let path: String
    var name: String { (path as NSString).lastPathComponent }
}

/// Protocol v2 endpoint logic. Thread-safe; UI reads it through `snapshot()`.
/// TR: Protokol v2 uç nokta mantığı. İş parçacığı güvenlidir; arayüz onu `snapshot()` ile okur.
// Shared between the HTTP queues and the UI; every mutable field is guarded by `lock`.
final class Receiver: HTTPHandler, @unchecked Sendable {
    static let frameSize = 1 << 20

    private let lock = NSRecursiveLock()
    private var key: SymmetricKey
    private var transfers: [String: IncomingTransfer] = [:]
    private var downloads: [String: (name: String, size: Int64, sent: Int64, samples: [(Date, Int)], updated: Date)] = [:]
    private var outboxFiles: [OutboxFile] = []
    private var sendRequested = false
    private var saveDirURL: URL

    var macName: () -> String = { "Mac" }
    var onReceived: ((URL, Int64) -> Void)?
    var onDeviceSeen: ((String) -> Void)?
    var onDirectEnd: (() -> Void)?
    /// A phone received every byte of an outbox file.
    /// TR: Bir telefon, giden kutusundaki bir dosyanın tamamını aldı.
    var onDownloaded: ((String) -> Void)?

    init(key: Data, saveDir: URL) {
        self.key = SymmetricKey(data: key)
        self.saveDirURL = saveDir
    }

    // MARK: settings

    func setKey(_ k: Data) { lock.locked { key = SymmetricKey(data: k) } }
    func setSaveDir(_ u: URL) { lock.locked { saveDirURL = u } }
    func setOutbox(_ files: [OutboxFile]) { lock.locked { outboxFiles = files } }
    /// "Send" was pressed: a phone that lists the outbox should download it without being asked.
    /// TR: "Gönder"e basıldı: giden kutusunu listeleyen telefon, sorulmadan indirmelidir.
    func setSendRequested(_ on: Bool) { lock.locked { sendRequested = on } }

    private var saveDir: URL { lock.locked { saveDirURL } }
    private var partialDir: URL {
        let d = saveDir.appendingPathComponent(".partial", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private var currentKey: SymmetricKey { lock.locked { key } }

    // MARK: UI snapshot

    func snapshot() -> [TransferSnapshot] {
        lock.locked {
            let now = Date()
            func speed(_ s: [(Date, Int)]) -> Double {
                Double(s.filter { now.timeIntervalSince($0.0) < 3 }.reduce(0) { $0 + $1.1 }) / 3
            }
            let incoming = transfers.values.filter { now.timeIntervalSince($0.updated) < 30 }.map {
                TransferSnapshot(id: $0.id, name: $0.name, size: $0.size, received: min($0.size, $0.doneBytes + $0.live),
                                 speed: speed($0.samples), direction: .incoming)
            }
            let outgoing = downloads.filter { now.timeIntervalSince($0.value.updated) < 5 }.map {
                TransferSnapshot(id: "dl-" + $0.key, name: $0.value.name, size: $0.value.size, received: $0.value.sent,
                                 speed: speed($0.value.samples), direction: .outgoing)
            }
            return (incoming + outgoing).sorted { $0.name < $1.name }
        }
    }

    // MARK: HTTPHandler

    func bodyProgress(_ head: HTTPRequest, delta: Int) {
        guard head.path == "/api/chunk", let id = head.query["id"] else { return }
        lock.locked {
            guard let t = transfers[id] else { return }
            t.live += Int64(delta)
            t.updated = Date()
            if delta > 0 {
                t.samples.append((Date(), delta))
                if t.samples.count > 400 { t.samples.removeFirst(t.samples.count - 400) }
            }
        }
    }

    func handle(_ req: HTTPRequest, respond: @escaping (HTTPResponse) -> Void) {
        guard authorized(req) else { return respond(.error(403, "unauthorized")) }
        switch (req.method, req.path) {
        case ("POST", "/api/hello"): respond(hello(req))
        case ("POST", "/api/init"): respond(initTransfer(req))
        case ("PUT", "/api/chunk"): respond(chunk(req))
        case ("POST", "/api/finish"): respond(finish(req))
        case ("POST", "/api/cancel"): respond(cancel(req))
        case ("POST", "/api/outbox"): respond(outbox(req))
        case ("POST", "/api/folders"): respond(folders(req))
        case ("GET", "/api/dl"): respond(download(req))
        case ("POST", "/api/direct/end"):
            respond(sealed(req, [:]))
            onDirectEnd?()
        default: respond(.error(404, "not found"))
        }
    }

    // MARK: crypto helpers

    private func authorized(_ req: HTTPRequest) -> Bool {
        guard let token = req.headers["x-pd-auth"], let box = Data(base64url: token),
              let plain = try? PDCrypto.open(box, key: currentKey, aad: "pd2 auth"),
              let text = String(data: plain, encoding: .utf8) else { return false }
        let parts = text.split(separator: " ", maxSplits: 2).map(String.init)
        guard parts.count == 3, parts[0] == req.method, parts[1] == req.target,
              let ts = Double(parts[2]) else { return false }
        return abs(Date().timeIntervalSince1970 - ts) < 300
    }

    private func openJSON(_ req: HTTPRequest) -> [String: Any]? {
        if req.body.isEmpty { return [:] }
        guard let plain = try? PDCrypto.open(req.body, key: currentKey, aad: "pd2 req \(req.path)") else { return nil }
        return (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any]
    }

    private func sealed(_ req: HTTPRequest, _ obj: [String: Any]) -> HTTPResponse {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let box = try? PDCrypto.seal(data, key: currentKey, aad: "pd2 res \(req.path)") else {
            return .error(500, "encryption failed")
        }
        return HTTPResponse(status: 200, body: box)
    }

    // MARK: endpoints

    private func hello(_ req: HTTPRequest) -> HTTPResponse {
        guard let body = openJSON(req) else { return .error(400, "bad body") }
        if let device = body["device"] as? String, !device.isEmpty { onDeviceSeen?(device) }
        return sealed(req, ["name": macName(), "v": 2])
    }

    private func transfer(_ id: String?) -> IncomingTransfer? {
        guard let id, id.count == 32, id.allSatisfy({ $0.isHexDigit }) else { return nil }
        return lock.locked {
            if let t = transfers[id] { return t }
            let meta = partialDir.appendingPathComponent(id + ".json")
            let part = partialDir.appendingPathComponent(id + ".part")
            guard FileManager.default.fileExists(atPath: part.path),
                  let data = try? Data(contentsOf: meta),
                  let m = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let name = m["name"] as? String, let size = (m["size"] as? NSNumber)?.int64Value,
                  let chunk = m["chunk"] as? Int else { return nil }
            let t = IncomingTransfer(id: id, name: name, size: size, mtime: (m["mtime"] as? NSNumber)?.int64Value ?? 0,
                                     chunk: chunk, done: Set(m["done"] as? [Int] ?? []))
            t.folder = Self.safeFolder(m["dir"] as? String ?? "")
            transfers[id] = t
            return t
        }
    }

    private func saveMeta(_ t: IncomingTransfer) {
        // Serialized so an older snapshot never overwrites a newer one
        lock.locked {
            let obj: [String: Any] = ["name": t.name, "size": t.size, "mtime": t.mtime, "chunk": t.chunk,
                                      "done": t.done.sorted(), "dir": t.folder]
            if let data = try? JSONSerialization.data(withJSONObject: obj) {
                try? data.write(to: partialDir.appendingPathComponent(t.id + ".json"), options: .atomic)
            }
        }
    }

    static func safeName(_ raw: String) -> String {
        var name = (raw.replacingOccurrences(of: "\\", with: "/") as NSString).lastPathComponent
        name = String(name.unicodeScalars.map { $0.value < 32 || $0 == "/" || $0 == ":" ? "_" : Character($0) })
        name = name.trimmingCharacters(in: .whitespaces)
        while name.hasPrefix(".") { name.removeFirst() }
        if name.isEmpty { name = "file" }
        if name.utf8.count > 200 {
            let ext = (name as NSString).pathExtension
            name = String(name.prefix(150)) + (ext.isEmpty ? "" : "." + ext.prefix(20))
        }
        return name
    }

    /// Cleans a phone-supplied relative folder: no "..", no absolute paths, at most four levels.
    /// TR: Telefondan gelen göreli klasör adını temizler: ".." yok, mutlak yol yok, en fazla dört düzey.
    static func safeFolder(_ raw: String) -> String {
        raw.replacingOccurrences(of: "\\", with: "/").split(separator: "/")
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ". ")) }
            .filter { !$0.isEmpty }
            .prefix(4)
            .map { safeName($0) }
            .joined(separator: "/")
    }

    /// Subfolders of the download folder, newest first, so the phone can offer them.
    /// TR: Telefon önerebilsin diye kayıt klasörünün alt klasörleri, en yeniden eskiye.
    private func folders(_ req: HTTPRequest) -> HTTPResponse {
        let keys: [URLResourceKey] = [.isDirectoryKey, .contentModificationDateKey]
        let items = (try? FileManager.default.contentsOfDirectory(at: saveDir, includingPropertiesForKeys: keys,
                                                                  options: [.skipsHiddenFiles])) ?? []
        let dirs = items.compactMap { u -> (String, Date)? in
            guard let v = try? u.resourceValues(forKeys: Set(keys)), v.isDirectory == true else { return nil }
            return (u.lastPathComponent, v.contentModificationDate ?? .distantPast)
        }.sorted { $0.1 > $1.1 }.prefix(30).map(\.0)
        return sealed(req, ["root": saveDir.lastPathComponent, "folders": Array(dirs)])
    }

    private func initTransfer(_ req: HTTPRequest) -> HTTPResponse {
        guard let b = openJSON(req), let rawName = b["name"] as? String,
              let size = (b["size"] as? NSNumber)?.int64Value, size >= 0 else { return .error(400, "bad request") }
        let mtime = (b["mtime"] as? NSNumber)?.int64Value ?? 0
        let chunk = (b["chunk"] as? NSNumber)?.intValue ?? (8 << 20)
        guard (1 << 20)...(64 << 20) ~= chunk else { return .error(400, "bad chunk size") }
        let name = Self.safeName(rawName)
        let id = PDCrypto.transferId(name: name, size: size, mtime: mtime, chunk: chunk)
        let t: IncomingTransfer
        if let existing = transfer(id) {
            t = existing
        } else {
            t = IncomingTransfer(id: id, name: name, size: size, mtime: mtime, chunk: chunk)
            let part = partialDir.appendingPathComponent(id + ".part")
            guard FileManager.default.createFile(atPath: part.path, contents: nil),
                  let fh = try? FileHandle(forWritingTo: part) else { return .error(500, "cannot create file") }
            try? fh.truncate(atOffset: UInt64(size))
            try? fh.close()
            saveMeta(t)
            lock.locked { transfers[id] = t }
        }
        // The destination can change between attempts; the latest request wins
        let folder = Self.safeFolder(b["dir"] as? String ?? "")
        let changed = lock.locked { () -> Bool in defer { t.folder = folder }; return t.folder != folder }
        if changed { saveMeta(t) }
        let done = lock.locked { () -> [Int] in t.updated = Date(); return t.done.sorted() }
        return sealed(req, ["id": id, "chunk": t.chunk, "done": done])
    }

    private func chunk(_ req: HTTPRequest) -> HTTPResponse {
        let t0 = transfer(req.query["id"])
        defer { lock.locked { t0?.live -= Int64(req.body.count) } }
        guard let t = t0, let i = Int(req.query["i"] ?? ""),
              i >= 0, i < t.chunkCount else { return .error(404, "unknown transfer") }
        guard let plain = try? PDCrypto.open(req.body, key: currentKey, aad: "pd2 chunk \(t.id) \(i)") else {
            return .error(400, "decryption failed")
        }
        guard plain.count == t.chunkLength(i) else { return .error(400, "wrong chunk length") }
        let part = partialDir.appendingPathComponent(t.id + ".part")
        do {
            let fh = try FileHandle(forWritingTo: part)
            try fh.seek(toOffset: UInt64(i) * UInt64(t.chunk))
            try fh.write(contentsOf: plain)
            try fh.close()
        } catch {
            return .error(500, "write failed")
        }
        lock.locked {
            t.done.insert(i)
            t.updated = Date()
        }
        saveMeta(t)
        return sealed(req, [:])
    }

    private func finish(_ req: HTTPRequest) -> HTTPResponse {
        guard let t = transfer(req.query["id"]) else { return .error(404, "unknown transfer") }
        let missing = lock.locked { t.chunkCount - t.done.count }
        if missing > 0 { return .error(409, "\(missing) chunks missing") }
        let part = partialDir.appendingPathComponent(t.id + ".part")
        let folder = lock.locked { t.folder }
        let dest = folder.isEmpty ? saveDir : saveDir.appendingPathComponent(folder, isDirectory: true)
        let final = uniqueURL(dest.appendingPathComponent(t.name))
        do {
            try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
            try FileManager.default.moveItem(at: part, to: final)
        } catch {
            return .error(500, "cannot save file")
        }
        if t.mtime > 0 {
            let date = Date(timeIntervalSince1970: Double(t.mtime) / 1000)
            try? FileManager.default.setAttributes([.modificationDate: date, .creationDate: date], ofItemAtPath: final.path)
        }
        try? FileManager.default.removeItem(at: partialDir.appendingPathComponent(t.id + ".json"))
        lock.locked { _ = transfers.removeValue(forKey: t.id) }
        onReceived?(final, t.size)
        return sealed(req, ["name": final.lastPathComponent])
    }

    private func uniqueURL(_ url: URL) -> URL {
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) { return url }
        let dir = url.deletingLastPathComponent()
        let ext = url.pathExtension
        let stem = url.deletingPathExtension().lastPathComponent
        var n = 1
        while true {
            let candidate = dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(n))" : "\(stem) (\(n)).\(ext)")
            if !fm.fileExists(atPath: candidate.path) { return candidate }
            n += 1
        }
    }

    private func cancel(_ req: HTTPRequest) -> HTTPResponse {
        if let t = transfer(req.query["id"]) {
            lock.locked { _ = transfers.removeValue(forKey: t.id) }
            try? FileManager.default.removeItem(at: partialDir.appendingPathComponent(t.id + ".part"))
            try? FileManager.default.removeItem(at: partialDir.appendingPathComponent(t.id + ".json"))
        }
        return sealed(req, [:])
    }

    /// Deletes unfinished uploads older than a week.
    /// TR: Bir haftadan eski, tamamlanmamış yüklemeleri siler.
    func cleanupPartials() {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: partialDir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        for f in items {
            let date = (try? f.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
            if Date().timeIntervalSince(date) > 7 * 24 * 3600 { try? fm.removeItem(at: f) }
        }
    }

    // MARK: Mac → phone

    private func outbox(_ req: HTTPRequest) -> HTTPResponse {
        let files = lock.locked { outboxFiles }.compactMap { f -> [String: Any]? in
            guard let attrs = try? FileManager.default.attributesOfItem(atPath: f.path),
                  (attrs[.type] as? FileAttributeType) == .typeRegular else { return nil }
            let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
            let mtime = Int64(((attrs[.modificationDate] as? Date) ?? Date()).timeIntervalSince1970 * 1000)
            return ["id": f.id, "name": f.name, "size": size, "mtime": mtime]
        }
        return sealed(req, ["files": files, "send": lock.locked { sendRequested }])
    }

    private func download(_ req: HTTPRequest) -> HTTPResponse {
        guard let fid = req.query["f"], let file = lock.locked({ outboxFiles.first { $0.id == fid } }),
              let fh = FileHandle(forReadingAtPath: file.path),
              let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value
        else { return .error(404, "file not found") }
        let frame = Int64(Self.frameSize)
        let frames = Int((size + frame - 1) / frame)
        let from = max(0, min(Int(req.query["from"] ?? "0") ?? 0, frames))
        var total: Int64 = 0
        for k in from..<frames { total += 4 + 28 + min(frame, size - Int64(k) * frame) }
        let key = currentKey
        let dlKey = fid + "-" + UUID().uuidString
        lock.locked { downloads[dlKey] = (file.name, size, Int64(from) * frame, [], Date()) }

        var resp = HTTPResponse(status: 200)
        resp.streamLength = total
        resp.stream = { [weak self] writer in
            var k = from
            func next() {
                guard k < frames else {
                    try? fh.close()
                    self?.lock.locked { _ = self?.downloads.removeValue(forKey: dlKey) }
                    writer.finish(ok: true)
                    self?.onDownloaded?(fid)
                    return
                }
                do {
                    try fh.seek(toOffset: UInt64(Int64(k) * frame))
                    let plain = try fh.read(upToCount: Self.frameSize) ?? Data()
                    let box = try PDCrypto.seal(plain, key: key, aad: "pd2 dl \(fid) \(k)")
                    var len = UInt32(box.count).bigEndian
                    let out = Data(bytes: &len, count: 4) + box
                    writer.write(out) { ok in
                        guard ok else {
                            try? fh.close()
                            self?.lock.locked { _ = self?.downloads.removeValue(forKey: dlKey) }
                            writer.finish(ok: false)
                            return
                        }
                        self?.lock.locked {
                            if var d = self?.downloads[dlKey] {
                                d.sent += Int64(plain.count)
                                d.samples.append((Date(), plain.count))
                                if d.samples.count > 200 { d.samples.removeFirst(d.samples.count - 200) }
                                d.updated = Date()
                                self?.downloads[dlKey] = d
                            }
                        }
                        k += 1
                        next()
                    }
                } catch {
                    try? fh.close()
                    writer.finish(ok: false)
                }
            }
            next()
        }
        return resp
    }
}
