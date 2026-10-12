import AppKit
import SwiftUI

/// The "Warm Modern Utility" design system: sunshine yellow on warm paper tones, in light and dark.
/// TR: "Warm Modern Utility" tasarım sistemi: sıcak kâğıt tonları üzerinde güneş sarısı; açık ve koyu.
enum Theme {
    static func ns(_ light: Int, _ dark: Int) -> NSColor {
        NSColor(name: nil) { appearance in
            let hex = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255,
                           blue: CGFloat(hex & 255) / 255, alpha: 1)
        }
    }

    static let bgNS = ns(0xFFFBF0, 0x1F1D18)
    static let bg = Color(nsColor: bgNS)
    static let card = Color(nsColor: ns(0xFFFFFF, 0x2A2720))
    static let fg = Color(nsColor: ns(0x2B2A28, 0xF5F0E1))
    static let mute = Color(nsColor: ns(0x8A857A, 0x9E988A))
    static let line = Color(nsColor: ns(0xEFEAE0, 0x38342B))
    /// Brand yellow: fills only. Text on it is `onAcc`.
    /// TR: Marka sarısı: yalnızca dolgu. Üzerindeki metin `onAcc` rengindedir.
    static let acc = Color(nsColor: ns(0xFFD230, 0xFFD230))
    static let onAcc = Color(nsColor: ns(0x2B2A28, 0x2B2A28))
    static let tint = Color(nsColor: ns(0xFFF4C7, 0x38342B))
    /// Yellow can't be read as text on paper, so links use its dark shade there.
    /// TR: Sarı, kâğıt üzerinde metin olarak okunmaz; bağlantılar orada koyu tonunu kullanır.
    static let link = Color(nsColor: ns(0x735C00, 0xFFD230))
    static let okSoft = Color(nsColor: ns(0xCDEFE0, 0x24402F))
    static let onOk = Color(nsColor: ns(0x1F6B4A, 0x7CCFA8))

    /// Pastel colour for a file's type, from its name.
    /// TR: Dosya adından, türüne göre pastel renk.
    static func typeColor(_ name: String) -> Color {
        let hex: Int
        switch (name as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "dng", "raw", "bmp", "svg": hex = 0xCFE6F7
        case "mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v": hex = 0xF9D5DF
        case "mp3", "wav", "m4a", "flac", "ogg", "aac", "opus": hex = 0xFFD9C7
        case "zip", "rar", "7z", "tar", "gz", "apk", "dmg", "iso": hex = 0xE1DAF7
        default: hex = 0xCDEFE0
        }
        return Color(nsColor: ns(hex, hex))
    }
}

/// Pill button: yellow for the one primary action on a screen, soft tint for the rest.
/// TR: Hap biçimli buton: ekrandaki tek ana eylem için sarı, diğerleri için yumuşak ton.
struct PillButton: ButtonStyle {
    var primary = false
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .semibold, design: .rounded))
            .foregroundStyle(primary ? Theme.onAcc : Theme.fg)
            .lineLimit(1).fixedSize()
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(primary ? Theme.acc : Theme.tint, in: Capsule())
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.45)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

/// The small pastel square showing a file's extension.
/// TR: Dosyanın uzantısını gösteren küçük pastel kare.
struct TypePlate: View {
    let name: String

    var body: some View {
        Text(verbatim: String((name as NSString).pathExtension.uppercased().prefix(4)))
            .font(.system(size: 8, weight: .bold, design: .rounded))
            .foregroundStyle(Theme.onAcc)
            .frame(width: 28, height: 28)
            .background(Theme.typeColor(name), in: RoundedRectangle(cornerRadius: 8))
    }
}

extension View {
    /// White (or espresso) rounded card with a hairline border.
    /// TR: İnce kenarlıklı, beyaz (ya da koyu kahve) yuvarlak köşeli kart.
    func card(padding: CGFloat = 12) -> some View {
        self.padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.line))
    }
}
