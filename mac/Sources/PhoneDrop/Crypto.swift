import CryptoKit
import Foundation

/// AES-256-GCM helpers (see docs/PROTOCOL.md).
/// TR: AES-256-GCM yardımcıları (bkz. docs/PROTOCOL.md).
enum PDCrypto {
    static func seal(_ data: Data, key: SymmetricKey, aad: String) throws -> Data {
        let box = try AES.GCM.seal(data, using: key, nonce: AES.GCM.Nonce(), authenticating: Data(aad.utf8))
        return box.combined!
    }

    static func open(_ data: Data, key: SymmetricKey, aad: String) throws -> Data {
        let box = try AES.GCM.SealedBox(combined: data)
        return try AES.GCM.open(box, using: key, authenticating: Data(aad.utf8))
    }

    static func kid(_ key: Data) -> String {
        let digest = SHA256.hash(data: Data("phonedrop-kid".utf8) + key)
        return digest.map { String(format: "%02x", $0) }.joined().prefix(16).description
    }

    static func transferId(name: String, size: Int64, mtime: Int64, chunk: Int) -> String {
        let digest = Insecure.SHA1.hash(data: Data("\(name)|\(size)|\(mtime)|\(chunk)".utf8))
        return String(digest.map { String(format: "%02x", $0) }.joined().prefix(32))
    }
}

extension Data {
    func base64url() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    init?(base64url s: String) {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        self.init(base64Encoded: t)
    }
}

/// Stores the pairing key in Application Support.
/// TR: Eşleştirme anahtarını Application Support klasöründe saklar.
enum KeyStore {
    static var dir: URL {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PhoneDrop", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static var file: URL { dir.appendingPathComponent("key") }

    static func load() -> Data {
        if let d = try? Data(contentsOf: file), d.count == 32 { return d }
        return reset()
    }

    @discardableResult
    static func reset() -> Data {
        let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        try? key.write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        return key
    }
}
