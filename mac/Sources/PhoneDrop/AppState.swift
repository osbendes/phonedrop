import AppKit
import CoreImage.CIFilterBuiltins
import Foundation
import Network
import ServiceManagement
import UserNotifications

func L(_ key: String) -> String { NSLocalizedString(key, comment: "") }

struct RecentFile: Identifiable, Codable {
    var id: String { path + "\(time)" }
    let path: String
    let size: Int64
    let time: Date
    var name: String { (path as NSString).lastPathComponent }
}

@MainActor
final class AppState: ObservableObject {
    static let androidAppURL = URL(string: "https://github.com/osbendes/phonedrop/releases/latest")!

    @Published var transfers: [TransferSnapshot] = []
    @Published var recent: [RecentFile] = []
    @Published var outbox: [OutboxFile] = []
    @Published var devices: [String] = []
    @Published var saveDir: URL
    @Published var port: UInt16 = 0
    @Published var ip: String = "–"
    @Published var showPair = false
    @Published var dropTargeted = false
    @Published var launchAtLogin = SMAppService.mainApp.status == .enabled

    private var key: Data
    private let server = HTTPServer()
    private let receiver: Receiver
    private var timer: Timer?
    private var activity: NSObjectProtocol?
    private var idleSince: Date?
    private let defaults = UserDefaults.standard

    init() {
        let dir = defaults.string(forKey: "saveDir").map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("PhoneDrop")
        saveDir = dir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        key = KeyStore.load()
        receiver = Receiver(key: key, saveDir: dir)
        devices = defaults.stringArray(forKey: "devices") ?? []
        if let data = defaults.data(forKey: "outbox"), let list = try? JSONDecoder().decode([OutboxFile].self, from: data) {
            outbox = list.filter { FileManager.default.fileExists(atPath: $0.path) }
        }
        if let data = defaults.data(forKey: "recent"), let list = try? JSONDecoder().decode([RecentFile].self, from: data) {
            recent = list
        }
        receiver.setOutbox(outbox)
        receiver.macName = { Host.current().localizedName ?? "Mac" }
        receiver.onReceived = { [weak self] url, size in
            Task { @MainActor in self?.received(url, size: size) }
        }
        receiver.onDeviceSeen = { [weak self] name in
            Task { @MainActor in self?.deviceSeen(name) }
        }
        receiver.cleanupPartials()

        server.handler = receiver
        server.onReady = { [weak self] port in
            Task { @MainActor in self?.port = port }
        }
        server.start(preferredPort: 8765, service: service())

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        ip = Self.localIPv4() ?? "–"
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    // MARK: pairing

    var kid: String { PDCrypto.kid(key) }

    private func service() -> NWListener.Service {
        let txt = NWTXTRecord(["kid": kid, "v": "2"])
        return NWListener.Service(name: "PhoneDrop – \(Host.current().localizedName ?? "Mac")", type: "_phonedrop._tcp",
                                  domain: nil, txtRecord: txt)
    }

    var pairURL: String {
        var c = URLComponents()
        c.scheme = "phonedrop"
        c.host = "pair"
        c.queryItems = [
            .init(name: "v", value: "2"),
            .init(name: "k", value: key.base64url()),
            .init(name: "h", value: ip),
            .init(name: "p", value: String(port)),
            .init(name: "n", value: Host.current().localizedName ?? "Mac"),
        ]
        return c.string ?? ""
    }

    func resetPairing() {
        key = KeyStore.reset()
        receiver.setKey(key)
        server.updateService(service())
        devices = []
        defaults.set(devices, forKey: "devices")
        objectWillChange.send()
    }

    private func deviceSeen(_ name: String) {
        devices.removeAll { $0 == name }
        devices.insert(name, at: 0)
        defaults.set(Array(devices.prefix(10)), forKey: "devices")
    }

    // MARK: polling

    private func tick() {
        let snap = receiver.snapshot()
        if snap.map(\.received) != transfers.map(\.received) || snap.count != transfers.count {
            transfers = snap
        }
        let newIP = Self.localIPv4() ?? "–"
        if newIP != ip { ip = newIP }
        // Keep the Mac awake while anything is moving, and 60 s after
        if !snap.isEmpty {
            idleSince = nil
            if activity == nil {
                activity = ProcessInfo.processInfo.beginActivity(options: [.idleSystemSleepDisabled, .userInitiated],
                                                                 reason: "PhoneDrop transfer")
            }
        } else if let a = activity {
            if idleSince == nil { idleSince = Date() }
            if Date().timeIntervalSince(idleSince!) > 60 {
                ProcessInfo.processInfo.endActivity(a)
                activity = nil
            }
        }
    }

    private func received(_ url: URL, size: Int64) {
        recent.insert(RecentFile(path: url.path, size: size, time: Date()), at: 0)
        recent = Array(recent.prefix(20))
        if let data = try? JSONEncoder().encode(recent) { defaults.set(data, forKey: "recent") }
        let content = UNMutableNotificationContent()
        content.title = L("File received")
        content.body = "\(url.lastPathComponent) · \(Fmt.bytes(size))"
        content.sound = .default
        content.userInfo = ["path": url.path]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    // MARK: actions

    func openSaveDir() { NSWorkspace.shared.open(saveDir) }

    func reveal(_ path: String) {
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
    }

    func chooseSaveDir() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.directoryURL = saveDir
        panel.prompt = L("Choose")
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK, let u = panel.url {
            saveDir = u
            defaults.set(u.path, forKey: "saveDir")
            receiver.setSaveDir(u)
        }
    }

    func addToOutbox() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = L("Add")
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { addToOutbox(panel.urls) }
    }

    func addToOutbox(_ urls: [URL]) {
        for u in urls where !outbox.contains(where: { $0.path == u.path }) {
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: u.path, isDirectory: &isDir), !isDir.boolValue {
                outbox.append(OutboxFile(id: UUID().uuidString.lowercased(), path: u.path))
            }
        }
        saveOutbox()
    }

    func removeFromOutbox(_ f: OutboxFile) {
        outbox.removeAll { $0.id == f.id }
        saveOutbox()
    }

    private func saveOutbox() {
        receiver.setOutbox(outbox)
        if let data = try? JSONEncoder().encode(outbox) { defaults.set(data, forKey: "outbox") }
    }

    func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            NSLog("Launch at login failed: \(error)")
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    // MARK: helpers

    static func qrImage(_ text: String, size: CGFloat) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let scale = size / out.extent.width
        let img = out.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: img)
        let ns = NSImage(size: rep.size)
        ns.addRepresentation(rep)
        return ns
    }

    /// The Mac's LAN IPv4, preferring Wi-Fi/Ethernet.
    static func localIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var candidates: [(String, String)] = []
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  (ifa.ifa_flags & UInt32(IFF_UP)) != 0, (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            let name = String(cString: ifa.ifa_name)
            if ip.hasPrefix("169.254.") { continue }
            candidates.append((name, ip))
        }
        let order = ["en0", "en1", "bridge100"]
        return candidates.sorted { (order.firstIndex(of: $0.0) ?? 99) < (order.firstIndex(of: $1.0) ?? 99) }.first?.1
    }
}

enum Fmt {
    static func bytes(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }

    static func eta(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds > 0 else { return "" }
        let f = DateComponentsFormatter()
        f.allowedUnits = seconds >= 3600 ? [.hour, .minute] : [.minute, .second]
        f.unitsStyle = .abbreviated
        return f.string(from: seconds) ?? ""
    }
}
