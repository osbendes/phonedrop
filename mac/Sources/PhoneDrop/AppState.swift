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

/// Everything the UI shows, and the glue between the parts: settings, the HTTP server, the receiver, direct
/// mode, notifications and keeping the Mac awake during transfers.
/// TR: Arayüzün gösterdiği her şey ve parçaları birbirine bağlayan katman: ayarlar, HTTP sunucusu, alıcı,
/// doğrudan mod, bildirimler ve aktarım sırasında Mac'i uyanık tutma.
@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()
    static let androidAppURL = URL(string: "https://osbendes.github.io/phonedrop/")!
    /// Direct APK link, used for the download QR code
    /// TR: İndirme karekodu için doğrudan APK bağlantısı
    static let androidApkURL = "https://github.com/osbendes/phonedrop/releases/latest/download/PhoneDrop.apk"
    static var defaultSaveDir: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("PhoneDrop")
    }

    @Published var transfers: [TransferSnapshot] = []
    @Published var recent: [RecentFile] = []
    @Published var outbox: [OutboxFile] = []
    @Published var devices: [String] = []
    @Published var saveDir: URL
    @Published var port: UInt16 = 0
    @Published var ip: String = "–"
    /// The pairing card shows the app-download QR instead of the pairing QR
    /// TR: Eşleştirme kartı, eşleştirme karekodu yerine uygulama indirme karekodunu gösterir
    @Published var qrShowsApp = false
    @Published var recentDirs: [String] = UserDefaults.standard.stringArray(forKey: "recentDirs") ?? []
    @Published var dropTargeted = false
    @Published var directSSID: String?
    /// "Send" was pressed and the phone hasn't fetched everything yet
    /// TR: "Gönder"e basıldı ve telefon henüz her şeyi indirmedi
    @Published var sending = false
    private var sendDeadline = Date.distantPast
    @Published var allowDirectOnWiFi = UserDefaults.standard.bool(forKey: "allowDirectOnWiFi") {
        didSet { defaults.set(allowDirectOnWiFi, forKey: "allowDirectOnWiFi") }
    }
    @Published var launchAtLogin = SMAppService.mainApp.status == .enabled

    private var key: Data
    private let server = HTTPServer()
    private let receiver: Receiver
    private let direct = DirectMode()
    private var timer: Timer?
    private var activity: NSObjectProtocol?
    private var idleSince: Date?
    private var ticks = 0
    private let defaults = UserDefaults.standard

    init() {
        let dir = defaults.string(forKey: "saveDir").map { URL(fileURLWithPath: $0) }
            ?? Self.defaultSaveDir
        saveDir = dir
        key = KeyStore.load()
        receiver = Receiver(key: key, saveDir: dir)
        devices = defaults.stringArray(forKey: "devices") ?? []
        if let data = defaults.data(forKey: "outbox"), let list = try? JSONDecoder().decode([OutboxFile].self, from: data) {
            outbox = list
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
        // Disk work stays off the main thread: the first touch of ~/Downloads waits for the user to
        // answer macOS's folder-access prompt, and the app must stay responsive meanwhile.
        let receiver = self.receiver
        let savedOutbox = outbox
        DispatchQueue.global(qos: .utility).async { [weak self] in
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            receiver.cleanupPartials()
            let existing = savedOutbox.filter { FileManager.default.fileExists(atPath: $0.path) }
            if existing.count != savedOutbox.count {
                Task { @MainActor in
                    self?.outbox = existing
                    self?.saveOutbox()
                }
            }
        }

        server.handler = receiver
        server.onReady = { [weak self] port in
            Task { @MainActor in self?.port = port }
        }
        server.start(preferredPort: 8765, service: service())

        // DirectMode calls these from its Bluetooth queue, so they must not touch main-actor state
        let server = self.server
        direct.key = { KeyStore.load() }
        direct.port = { server.port }
        direct.allowOnWiFi = { UserDefaults.standard.bool(forKey: "allowDirectOnWiFi") }
        direct.onStateChange = { [weak self] ssid in Task { @MainActor in self?.directSSID = ssid } }
        receiver.onDirectEnd = { [weak self] in self?.direct.end() }
        // Once the phone has a file, it no longer needs to be offered
        receiver.onDownloaded = { [weak self] id in
            Task { @MainActor in
                guard let self else { return }
                self.outbox.removeAll { $0.id == id }
                self.saveOutbox()
            }
        }
        direct.start()

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        ip = Self.localIPv4() ?? "–"
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        timer?.tolerance = 0.2  // lets macOS batch the wake-ups while idle
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
        // A phone we haven't seen before just paired: the pairing window has done its job
        if !devices.contains(name) { PairWindow.close() }
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
        // The address only matters for the pairing code; checking it every few seconds is plenty
        ticks += 1
        if ticks % 10 == 0 {
            let newIP = Self.localIPv4() ?? "–"
            if newIP != ip { ip = newIP }
        }
        if sending {
            // Keep signalling while files are moving; give up two minutes after the last activity
            if snap.contains(where: { $0.direction == .outgoing }) { sendDeadline = Date().addingTimeInterval(120) }
            if outbox.isEmpty || Date() > sendDeadline { stopSending() }
        }
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

    /// Empties the "Received" list; the files themselves stay where they are.
    /// TR: "Alınanlar" listesini boşaltır; dosyaların kendisi yerinde kalır.
    func clearRecent() {
        recent.removeAll()
        defaults.removeObject(forKey: "recent")
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
        if panel.runModal() == .OK, let u = panel.url { setSaveDir(u) }
    }

    /// Switches where incoming files are saved and remembers the previous places.
    /// TR: Gelen dosyaların kaydedileceği yeri değiştirir ve önceki yerleri hatırlar.
    func setSaveDir(_ u: URL) {
        guard u.path != saveDir.path else { return }
        recentDirs.removeAll { $0 == u.path || $0 == saveDir.path }
        recentDirs.insert(saveDir.path, at: 0)
        recentDirs = Array(recentDirs.filter { $0 != Self.defaultSaveDir.path }.prefix(5))
        defaults.set(recentDirs, forKey: "recentDirs")
        saveDir = u
        defaults.set(u.path, forKey: "saveDir")
        receiver.setSaveDir(u)
        DispatchQueue.global(qos: .utility).async {
            try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        }
    }

    /// "~/Downloads/PhoneDrop" style path for display.
    /// TR: Gösterim için "~/Downloads/PhoneDrop" biçiminde yol.
    static func displayPath(_ path: String) -> String {
        (path as NSString).abbreviatingWithTildeInPath
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

    /// Asks the phone to download the list now, by flagging it in the Bluetooth advertisement.
    /// TR: Bluetooth yayınına işaret koyarak telefondan listeyi şimdi indirmesini ister.
    func sendOutbox() {
        guard !outbox.isEmpty else { return }
        sending = true
        sendDeadline = Date().addingTimeInterval(120)
        receiver.setSendRequested(true)
        direct.setWaking(true)
    }

    private func stopSending() {
        sending = false
        receiver.setSendRequested(false)
        direct.setWaking(false)
    }

    func clearOutbox() {
        outbox.removeAll()
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
    /// TR: Mac'in yerel ağdaki IPv4 adresi; Wi-Fi/Ethernet önceliklidir.
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
