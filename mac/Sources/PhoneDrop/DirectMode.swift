import CoreBluetooth
import CryptoKit
import Foundation
import os

private let log = Logger(subsystem: "com.osbendes.phonedrop", category: "direct")

/// Direct mode (docs/PROTOCOL.md): a Bluetooth LE handshake that makes the Mac join the phone's
/// local-only hotspot when there's no shared network.
final class DirectMode: NSObject, CBPeripheralManagerDelegate {
    private static let suffix = "-9D4B-4F2A-8E61-50484F4E4544"
    static let serviceUUID = CBUUID(string: "C0DE0001" + suffix)
    static let infoUUID = CBUUID(string: "C0DE0002" + suffix)
    static let joinUUID = CBUUID(string: "C0DE0003" + suffix)
    static let statusUUID = CBUUID(string: "C0DE0004" + suffix)
    /// Advertised instead of `serviceUUID` while the Mac has files it wants the phone to fetch now
    static let wakeUUID = CBUUID(string: "C0DE0005" + suffix)

    var key: () -> Data = { Data() }
    var port: () -> UInt16 = { 0 }
    var allowOnWiFi: () -> Bool = { false }
    /// Called on the main queue with the SSID being joined, or nil when back to normal.
    var onStateChange: ((String?) -> Void)?

    private let q = DispatchQueue(label: "phonedrop.ble")
    private var manager: CBPeripheralManager?
    private let lock = NSLock()
    private var status: [String: Any] = ["st": "idle"]
    private var joinedSSID: String?

    private var watchdog: DispatchSourceTimer?
    private var waking = false
    private var wifiInterface: String?

    /// Tells nearby paired phones (which scan for `wakeUUID` in the background) to fetch the outbox.
    func setWaking(_ on: Bool) {
        q.async {
            guard self.waking != on else { return }
            self.waking = on
            log.info("wake signal \(on ? "on" : "off", privacy: .public)")
            if let p = self.manager, p.state == .poweredOn {
                p.stopAdvertising()
                self.advertise(p)
            }
        }
    }

    /// One 128-bit UUID is all that fits in an advertisement next to the name, so it's one or the other.
    private func advertise(_ p: CBPeripheralManager) {
        p.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [waking ? Self.wakeUUID : Self.serviceUUID],
                            CBAdvertisementDataLocalNameKey: "PhoneDrop"])
    }

    func start() {
        manager = CBPeripheralManager(delegate: self, queue: q)
        // macOS can stop advertising (sleep, Bluetooth restarts); restart it if that happens
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + 15, repeating: 15)
        t.setEventHandler { [weak self] in
            guard let self, let p = self.manager, p.state == .poweredOn, !p.isAdvertising else { return }
            log.info("advertising stopped; restarting")
            self.peripheralManagerDidUpdateState(p)
        }
        t.resume()
        watchdog = t
    }

    // MARK: CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        log.info("state \(p.state.rawValue, privacy: .public) authorization \(CBManager.authorization.rawValue, privacy: .public)")
        guard p.state == .poweredOn else { return }
        let info = CBMutableCharacteristic(type: Self.infoUUID, properties: [.read], value: nil, permissions: [.readable])
        let join = CBMutableCharacteristic(type: Self.joinUUID, properties: [.write], value: nil, permissions: [.writeable])
        let status = CBMutableCharacteristic(type: Self.statusUUID, properties: [.read], value: nil, permissions: [.readable])
        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [info, join, status]
        p.removeAllServices()
        p.add(service)
    }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else { return log.error("add service: \(error!.localizedDescription, privacy: .public)") }
        advertise(p)
    }

    func peripheralManagerDidStartAdvertising(_ p: CBPeripheralManager, error: Error?) {
        if let error { log.error("advertising failed: \(error.localizedDescription, privacy: .public)") }
        else { log.info("advertising") }
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didSubscribeTo c: CBCharacteristic) {}

    func peripheralManager(_ p: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        log.info("read \(request.characteristic.uuid.uuidString, privacy: .public) offset \(request.offset)")
        let value: Data
        switch request.characteristic.uuid {
        case Self.infoUUID: value = Data(PDCrypto.kid(key()).utf8)
        case Self.statusUUID: value = sealedStatus()
        default: return p.respond(to: request, withResult: .attributeNotFound)
        }
        guard request.offset <= value.count else { return p.respond(to: request, withResult: .invalidOffset) }
        request.value = value.subdata(in: request.offset..<value.count)
        p.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first, first.characteristic.uuid == Self.joinUUID else {
            if let r = requests.first { p.respond(to: r, withResult: .writeNotPermitted) }
            return
        }
        // Long writes arrive as several requests with offsets
        var data = Data()
        for r in requests.sorted(by: { $0.offset < $1.offset }) {
            guard let v = r.value, r.offset == data.count else { return p.respond(to: first, withResult: .invalidOffset) }
            data.append(v)
        }
        p.respond(to: first, withResult: .success)
        log.info("join request, \(data.count) bytes")
        handleJoin(data)
    }

    // MARK: joining

    private func setStatus(_ s: [String: Any]) { lock.lock(); status = s; lock.unlock() }

    private func sealedStatus() -> Data {
        lock.lock()
        var s = status
        lock.unlock()
        s["t"] = Int(Date().timeIntervalSince1970)
        // Where the Mac is on its current network, so the phone can try that before starting a hotspot
        if wifiInterface == nil { wifiInterface = WiFi.interface() }
        if let iface = wifiInterface, let ip = WiFi.ipv4(iface) {
            s["lan"] = ip
            s["lp"] = Int(port())
        }
        guard let json = try? JSONSerialization.data(withJSONObject: s),
              let box = try? PDCrypto.seal(json, key: SymmetricKey(data: key()), aad: "pd2 ble status") else { return Data() }
        return box
    }

    private func handleJoin(_ box: Data) {
        guard let plain = try? PDCrypto.open(box, key: SymmetricKey(data: key()), aad: "pd2 ble join"),
              let obj = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let ssid = obj["s"] as? String, let pass = obj["p"] as? String,
              let t = (obj["t"] as? NSNumber)?.doubleValue, abs(Date().timeIntervalSince1970 - t) < 300 else {
            log.error("join request didn't open or is stale")
            return setStatus(["st": "error", "e": "bad-request"])
        }
        guard let iface = WiFi.interface() else { return setStatus(["st": "error", "e": "no-wifi-hardware"]) }
        lock.lock()
        let alreadyDirect = joinedSSID != nil
        lock.unlock()
        if !alreadyDirect, WiFi.ipv4(iface) != nil, !allowOnWiFi() {
            log.info("refusing: Mac is on Wi-Fi")
            return setStatus(["st": "error", "e": "on-wifi"])
        }
        setStatus(["st": "joining"])
        DispatchQueue.global().async { [weak self] in self?.join(iface: iface, ssid: ssid, pass: pass) }
    }

    private func join(iface: String, ssid: String, pass: String) {
        lock.lock(); joinedSSID = ssid; lock.unlock()
        DispatchQueue.main.async { self.onStateChange?(ssid) }
        let before = WiFi.ipv4(iface)
        // The hotspot has just been started; the Mac may need a few scans before it sees it
        var joined = false
        for attempt in 1...8 {
            let out = WiFi.run(["-setairportnetwork", iface, ssid, pass])
            if !out.localizedCaseInsensitiveContains("error") && !out.localizedCaseInsensitiveContains("could not") {
                joined = true
                break
            }
            log.info("join attempt \(attempt) failed: \(out.trimmingCharacters(in: .whitespacesAndNewlines), privacy: .public)")
            Thread.sleep(forTimeInterval: 2.5)
        }
        guard joined else {
            setStatus(["st": "error", "e": "join-failed"])
            end(keepStatus: true)
            return
        }
        // Wait for the phone's DHCP to give us an address
        for i in 0..<60 {
            // Ignore the old network's address unless it's still the same after a few seconds
            if let ip = WiFi.ipv4(iface), ip != before || i >= 10 {
                log.info("joined, ip \(ip, privacy: .public)")
                setStatus(["st": "ready", "ip": ip, "p": Int(port())])
                return
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        setStatus(["st": "error", "e": "no-address"])
        end(keepStatus: true)
    }

    /// The phone is done: forget its hotspot so macOS goes back to the usual Wi-Fi.
    func end(keepStatus: Bool = false) {
        lock.lock()
        let ssid = joinedSSID
        joinedSSID = nil
        lock.unlock()
        if !keepStatus { setStatus(["st": "idle"]) }
        guard let ssid, let iface = WiFi.interface() else { return }
        _ = WiFi.run(["-removepreferredwirelessnetwork", iface, ssid])
        DispatchQueue.main.async { self.onStateChange?(nil) }
    }
}

/// Thin wrapper around macOS's `networksetup`, which works on macOS 13+ without extra permissions.
enum WiFi {
    static func run(_ args: [String]) -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return "error: \(error)" }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// The Wi-Fi device name (en0 on laptops, often en1 on desktops with Ethernet).
    static func interface() -> String? {
        let lines = run(["-listallhardwareports"]).components(separatedBy: "\n")
        for (i, line) in lines.enumerated() where line.hasPrefix("Hardware Port:") &&
            (line.contains("Wi-Fi") || line.contains("AirPort")) {
            if i + 1 < lines.count, lines[i + 1].hasPrefix("Device:") {
                return lines[i + 1].replacingOccurrences(of: "Device:", with: "").trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    /// The interface's IPv4 address, ignoring self-assigned 169.254.x.x.
    static func ipv4(_ iface: String) -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  String(cString: ifa.ifa_name) == iface else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            if !ip.hasPrefix("169.254.") { return ip }
        }
        return nil
    }
}
