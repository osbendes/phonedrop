// Turns the raw screenshots from tools/screenshots.sh into the README images and the social stories.
//   swift tools/make_promo.swift <shots-dir> <readme-out-dir> <stories-out-dir>
// TR: tools/screenshots.sh'in aldığı ham ekran görüntülerini README görsellerine ve sosyal medya
// story'lerine dönüştürür.
import AppKit

let args = CommandLine.arguments
guard args.count == 4 else { fatalError("usage: make_promo.swift <shots-dir> <readme-out-dir> <stories-out-dir>") }
let (shots, readmeOut, storiesOut) = (args[1], args[2], args[3])
for dir in [readmeOut, storiesOut] { try! FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true) }

func load(_ name: String) -> CGImage {
    let url = URL(fileURLWithPath: "\(shots)/\(name).png")
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fatalError("missing \(url.path)") }
    return img
}

func rgb(_ hex: Int, _ a: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: a)
}

/// A bitmap with a top-left origin.
final class Canvas {
    let w: CGFloat, h: CGFloat
    let ctx: CGContext

    init(_ w: Int, _ h: Int) {
        self.w = CGFloat(w); self.h = CGFloat(h)
        ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: 1, y: -1)
        ctx.interpolationQuality = .high
    }

    func image(_ img: CGImage, in r: CGRect) {
        ctx.saveGState()
        ctx.translateBy(x: r.minX, y: r.maxY); ctx.scaleBy(x: 1, y: -1)
        ctx.draw(img, in: CGRect(origin: .zero, size: r.size))
        ctx.restoreGState()
    }

    func background(_ top: NSColor, _ bottom: NSColor) {
        let g = CGGradient(colorsSpace: nil, colors: [top.cgColor, bottom.cgColor] as CFArray, locations: nil)!
        ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: w * 0.35, y: h),
                               options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    }

    /// A soft light blob, to keep large flat areas from looking empty.
    func glow(x: CGFloat, y: CGFloat, radius: CGFloat, _ color: NSColor) {
        let g = CGGradient(colorsSpace: nil, colors: [color.cgColor, color.withAlphaComponent(0).cgColor] as CFArray, locations: nil)!
        ctx.drawRadialGradient(g, startCenter: CGPoint(x: x, y: y), startRadius: 0, endCenter: CGPoint(x: x, y: y), endRadius: radius, options: [])
    }

    func rounded(_ r: CGRect, _ radius: CGFloat) -> CGPath { CGPath(roundedRect: r, cornerWidth: radius, cornerHeight: radius, transform: nil) }

    func fill(_ path: CGPath, _ color: NSColor, shadow: CGFloat = 0) {
        ctx.saveGState()
        if shadow > 0 { ctx.setShadow(offset: CGSize(width: 0, height: -shadow * 0.4), blur: shadow, color: NSColor.black.withAlphaComponent(0.28).cgColor) }
        ctx.addPath(path); ctx.setFillColor(color.cgColor); ctx.fillPath()
        ctx.restoreGState()
    }

    func stroke(_ path: CGPath, _ color: NSColor, _ width: CGFloat) {
        ctx.addPath(path); ctx.setStrokeColor(color.cgColor); ctx.setLineWidth(width); ctx.strokePath()
    }

    /// Draws wrapped text and returns its height.
    @discardableResult
    func text(_ s: String, x: CGFloat, y: CGFloat, width: CGFloat, size: CGFloat, weight: NSFont.Weight = .regular,
              color: NSColor = ink, align: NSTextAlignment = .left, spacing: CGFloat = 0, kern: CGFloat = 0) -> CGFloat {
        let p = NSMutableParagraphStyle(); p.alignment = align; p.lineSpacing = spacing
        // The apps use the rounded system face; so do the pictures
        let base = NSFont.systemFont(ofSize: size, weight: weight)
        let font = base.fontDescriptor.withDesign(.rounded).flatMap { NSFont(descriptor: $0, size: size) } ?? base
        let str = NSAttributedString(string: s, attributes: [.font: font,
                                                             .foregroundColor: color, .paragraphStyle: p, .kern: kern])
        let box = str.boundingRect(with: CGSize(width: width, height: 10_000), options: [.usesLineFragmentOrigin])
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: true)
        str.draw(with: CGRect(x: x, y: y, width: width, height: ceil(box.height)), options: [.usesLineFragmentOrigin])
        NSGraphicsContext.restoreGraphicsState()
        return ceil(box.height)
    }

    /// A Mac window screenshot with rounded corners and a shadow. Returns its frame.
    @discardableResult
    func mac(_ name: String, x: CGFloat, y: CGFloat, width: CGFloat) -> CGRect {
        let img = load(name)
        let r = CGRect(x: x, y: y, width: width, height: width * CGFloat(img.height) / CGFloat(img.width))
        let path = rounded(r, width * 0.034)
        fill(path, .black, shadow: width * 0.09)
        ctx.saveGState(); ctx.addPath(path); ctx.clip(); image(img, in: r); ctx.restoreGState()
        stroke(path, NSColor.black.withAlphaComponent(0.10), 1.5)
        return r
    }

    static func macHeight(_ name: String, width: CGFloat) -> CGFloat {
        let img = load(name)
        return width * CGFloat(img.height) / CGFloat(img.width)
    }

    /// A phone screenshot inside a drawn phone. The status and navigation bars are cut off (they show
    /// whatever the phone was doing) and replaced by a plain strip. Returns the phone's frame.
    @discardableResult
    func phone(_ name: String, x: CGFloat, y: CGFloat, width: CGFloat) -> CGRect {
        let full = load(name)
        let (cutTop, cutBottom) = (150, 70)
        let img = full.cropping(to: CGRect(x: 0, y: cutTop, width: full.width, height: full.height - cutTop - cutBottom))!
        let bezel = width * 0.032
        let scale = (width - 2 * bezel) / CGFloat(img.width)
        let strip = 96 * scale
        let screen = CGRect(x: x + bezel, y: y + bezel, width: width - 2 * bezel, height: strip + CGFloat(img.height) * scale)
        let body = screen.insetBy(dx: -bezel, dy: -bezel)
        fill(rounded(body, width * 0.13), rgb(0x0B0C0F), shadow: width * 0.12)
        stroke(rounded(body.insetBy(dx: 1, dy: 1), width * 0.13), rgb(0x4A4E57), 2)
        ctx.saveGState()
        ctx.addPath(rounded(screen, width * 0.10)); ctx.clip()
        // The strip takes the colour of the screenshot's first row, so it also matches a dimmed dialog
        let one = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        one.draw(img.cropping(to: CGRect(x: 4, y: 0, width: 1, height: 1))!, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        let px = one.data!.assumingMemoryBound(to: UInt8.self)
        ctx.setFillColor(NSColor(srgbRed: CGFloat(px[0]) / 255, green: CGFloat(px[1]) / 255, blue: CGFloat(px[2]) / 255, alpha: 1).cgColor)
        ctx.fill(screen)
        image(img, in: CGRect(x: screen.minX, y: screen.minY + strip, width: screen.width, height: CGFloat(img.height) * scale))
        ctx.restoreGState()
        let cam = strip * 0.17
        ctx.setFillColor(rgb(0x0B0C0F).cgColor)
        ctx.fillEllipse(in: CGRect(x: screen.midX - cam, y: screen.minY + strip * 0.52 - cam, width: cam * 2, height: cam * 2))
        return body
    }

    static func phoneHeight(width: CGFloat) -> CGFloat {
        let bezel = width * 0.032
        return 2 * bezel + (96 + 2712 - 220) * (width - 2 * bezel) / 1220
    }

    func save(_ path: String) {
        let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
        try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
        print(path)
    }
}

let ink = rgb(0x2B2A28), cream = rgb(0xFFFBF0)

func brand(_ c: Canvas) {
    c.background(rgb(0xFFE27A), rgb(0xFFCB2E))
    c.glow(x: c.w * 0.85, y: c.h * 0.12, radius: c.w * 0.7, NSColor.white.withAlphaComponent(0.45))
    c.glow(x: c.w * 0.05, y: c.h * 0.95, radius: c.w * 0.8, rgb(0xFFB800, 0.5))
}

struct Texts {
    let lang: String
    let captions: [String: String]
    let stories: [(title: String, body: String)]
    let footer: String
}

let all = [
    Texts(lang: "en", captions: [
        "mac-pair": "First launch: scan to pair", "mac-receiving": "Receiving from the phone", "mac-sending": "Sending to the phone",
        "mac-light": "Light theme",
        "phone-unpaired": "First launch", "phone-sending": "Sending to the Mac", "phone-receiving": "Files from the Mac",
        "phone-settings": "Language and theme", "phone-light": "Light theme",
    ], stories: [
        ("Android to Mac,\nlike AirDrop", "Photos, videos, any file. No cable, no cloud, no account."),
        ("Pick. Send.\nDone.", "Choose from the gallery, or use Share › PhoneDrop in any app. Big files are fine: transfers resume where they stopped."),
        ("And back\nto the phone", "Drop files on the Mac app and press Send. They land in Downloads on your phone."),
        ("Encrypted.\nNothing leaves\nyour network.", "Pair once with a QR code. No Wi-Fi router around? Phone and Mac connect to each other directly."),
        ("Free and\nopen source", "No ads, no sign-up. English and Turkish, light and dark."),
    ], footer: "github.com/osbendes/phonedrop"),
    Texts(lang: "tr", captions: [
        "mac-pair": "İlk açılış: kodu okut, eşleş", "mac-receiving": "Telefondan dosya alırken", "mac-sending": "Telefona gönderirken",
        "mac-light": "Açık tema",
        "phone-unpaired": "İlk açılış", "phone-sending": "Mac'e gönderirken", "phone-receiving": "Mac'ten gelen dosyalar",
        "phone-settings": "Dil ve tema", "phone-light": "Açık tema",
    ], stories: [
        ("Android'den Mac'e,\nAirDrop gibi", "Fotoğraf, video, her türlü dosya. Kablo yok, bulut yok, hesap yok."),
        ("Seç. Gönder.\nBitti.", "Galeriden seç ya da herhangi bir uygulamada Paylaş › PhoneDrop de. Büyük dosyalar sorun değil: aktarım kaldığı yerden sürer."),
        ("Mac'ten\ntelefona da", "Dosyaları Mac uygulamasına bırak, Gönder'e bas. Telefonunda İndirilenler'e kaydedilir."),
        ("Şifreli.\nDosyaların ağından\ndışarı çıkmaz.", "Bir kez QR kodla eşleştir. Ortada modem yok mu? Telefon ve Mac birbirine doğrudan bağlanır."),
        ("Ücretsiz ve\naçık kaynak", "Reklam yok, üyelik yok. Türkçe ve İngilizce, açık ve koyu tema."),
    ], footer: "github.com/osbendes/phonedrop"),
]

for t in all {
    let l = t.lang

    // README: hero
    do {
        let c = Canvas(2000, 1180)
        brand(c)
        // The window as large as the canvas allows, the phone beside it; the pair is centred
        let pw: CGFloat = 430
        let mh = c.h - 140
        let mw = mh * 800 / Canvas.macHeight("mac-receiving-\(l)-dark", width: 800)
        let left = (c.w - mw - 70 - pw) / 2
        c.mac("mac-receiving-\(l)-dark", x: left, y: 70, width: mw)
        c.phone("phone-sending-\(l)-dark", x: left + mw + 70, y: (c.h - Canvas.phoneHeight(width: pw)) / 2, width: pw)
        c.save("\(readmeOut)/hero-\(l).png")
    }

    // README: the Mac app
    do {
        let items = [("mac-pair-\(l)-dark", "mac-pair"), ("mac-receiving-\(l)-dark", "mac-receiving"),
                     ("mac-sending-\(l)-dark", "mac-sending"), ("mac-ready-\(l)-light", "mac-light")]
        let w: CGFloat = 420, gap: CGFloat = 50, top: CGFloat = 70
        let tallest = items.map { Canvas.macHeight($0.0, width: w) }.max()!
        let c = Canvas(Int(gap + CGFloat(items.count) * (w + gap)), Int(top + tallest + 110))
        c.background(cream, rgb(0xFFF4C7))
        for (i, item) in items.enumerated() {
            let x = gap + CGFloat(i) * (w + gap)
            c.mac(item.0, x: x, y: top, width: w)
            c.text(t.captions[item.1]!, x: x, y: top + tallest + 34, width: w, size: 26, weight: .medium, color: ink, align: .center)
        }
        c.save("\(readmeOut)/mac-\(l).png")
    }

    // README: the Android app
    do {
        let items = [("phone-unpaired-\(l)-dark", "phone-unpaired"), ("phone-sending-\(l)-dark", "phone-sending"),
                     ("phone-receiving-\(l)-dark", "phone-receiving"), ("phone-settings-\(l)-dark", "phone-settings"),
                     ("phone-receiving-\(l)-light", "phone-light")]
        let w: CGFloat = 340, gap: CGFloat = 50, top: CGFloat = 70
        let ph = Canvas.phoneHeight(width: w)
        let c = Canvas(Int(gap + CGFloat(items.count) * (w + gap)), Int(top + ph + 110))
        c.background(cream, rgb(0xFFF4C7))
        for (i, item) in items.enumerated() {
            let x = gap + CGFloat(i) * (w + gap)
            c.phone(item.0, x: x, y: top, width: w)
            c.text(t.captions[item.1]!, x: x, y: top + ph + 34, width: w, size: 26, weight: .medium, color: ink, align: .center)
        }
        c.save("\(readmeOut)/android-\(l).png")
    }

    // Stories (1080x1920). The top and bottom ~250 px stay free of text: the apps draw their own UI there.
    for (i, s) in t.stories.enumerated() {
        let c = Canvas(1080, 1920)
        brand(c)
        c.text("PHONEDROP", x: 90, y: 270, width: 900, size: 30, weight: .bold, color: ink.withAlphaComponent(0.6), kern: 6)
        var y: CGFloat = 325
        y += c.text(s.title, x: 90, y: y, width: 900, size: 92, weight: .heavy, spacing: -6) + 28
        y += c.text(s.body, x: 90, y: y, width: 880, size: 38, weight: .medium, color: ink.withAlphaComponent(0.85), spacing: 6) + 60
        // Devices sit between the text and the footer
        let bottom: CGFloat = 1545
        let k = Canvas.phoneHeight(width: 1)
        func phoneWidth(_ most: CGFloat, height: CGFloat) -> CGFloat { min(most, height / k) }
        // A Mac window beside the phone, shrunk if it would be taller than the room there is
        func macBeside(_ name: String, phone pw: CGFloat) {
            let most = 1080 - pw - 110
            let width = min(most, most * (bottom - y - 20) / Canvas.macHeight(name, width: most))
            c.mac(name, x: 40 + (most - width) / 2, y: y + 20, width: width)
        }
        switch i {
        case 0:
            let pw = phoneWidth(410, height: bottom - y)
            macBeside("mac-receiving-\(l)-dark", phone: pw)
            c.phone("phone-sending-\(l)-dark", x: 1080 - pw - 40, y: bottom - k * pw, width: pw)
        case 1:
            let pw = phoneWidth(520, height: bottom - y)
            c.phone("phone-sending-\(l)-light", x: (1080 - pw) / 2, y: bottom - k * pw, width: pw)
        case 2:
            let pw = phoneWidth(410, height: bottom - y)
            macBeside("mac-sending-\(l)-light", phone: pw)
            c.phone("phone-receiving-\(l)-dark", x: 1080 - pw - 40, y: bottom - k * pw, width: pw)
        case 3:
            let pw = phoneWidth(380, height: bottom - y)
            macBeside("mac-paircode-\(l)-light", phone: pw)
            c.phone("phone-pairdialog-\(l)-dark", x: 1080 - pw - 40, y: bottom - k * pw, width: pw)
        default:
            let pw = phoneWidth(440, height: bottom - y)
            c.phone("phone-ready-\(l)-light", x: 540 - pw - 25, y: bottom - k * pw, width: pw)
            c.phone("phone-sending-\(l)-dark", x: 540 + 25, y: bottom - k * pw, width: pw)
        }
        let pill = CGRect(x: 240, y: 1590, width: 600, height: 76)
        c.fill(c.rounded(pill, 38), ink, shadow: 30)
        c.text(t.footer, x: pill.minX, y: pill.minY + 17, width: pill.width, size: 32, weight: .semibold, color: cream, align: .center)
        c.save("\(storiesOut)/story-\(l)-\(i + 1).png")
    }
}
