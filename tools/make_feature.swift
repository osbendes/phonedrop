// Play Store feature graphic (1024x500). Usage: swift make_feature.swift <out.png> <title> <tagline>
import AppKit

let args = CommandLine.arguments
let (out, title, tagline) = (args[1], args[2], args[3])
let W = 1024, H = 500
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: H, bitsPerSample: 8, samplesPerPixel: 4,
                           hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let full = NSRect(x: 0, y: 0, width: W, height: H)
NSGradient(starting: NSColor(red: 0.26, green: 0.52, blue: 1.0, alpha: 1),
           ending: NSColor(red: 0.11, green: 0.30, blue: 0.85, alpha: 1))!.draw(in: full, angle: -30)

// Glyph (same drawing as the app icon, scaled into a 420px box on the left)
let s: CGFloat = 420.0 / 1024.0
let ox: CGFloat = 20, oy: CGFloat = 40
func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> NSRect { NSRect(x: ox + x * s, y: oy + y * s, width: w * s, height: h * s) }
func p(_ x: CGFloat, _ y: CGFloat) -> NSPoint { NSPoint(x: ox + x * s, y: oy + y * s) }
NSColor.white.setFill(); NSColor.white.setStroke()
let phone = NSBezierPath(roundedRect: r(250, 330, 190, 360), xRadius: 34 * s, yRadius: 34 * s); phone.lineWidth = 26 * s; phone.stroke()
NSBezierPath(roundedRect: r(315, 362, 60, 14), xRadius: 7 * s, yRadius: 7 * s).fill()
let screen = NSBezierPath(roundedRect: r(560, 420, 250, 175), xRadius: 18 * s, yRadius: 18 * s); screen.lineWidth = 26 * s; screen.stroke()
NSBezierPath(roundedRect: r(520, 360, 330, 30), xRadius: 15 * s, yRadius: 15 * s).fill()
let shaft = NSBezierPath(); shaft.move(to: p(345, 735)); shaft.curve(to: p(685, 672), controlPoint1: p(390, 880), controlPoint2: p(660, 880))
shaft.lineWidth = 30 * s; shaft.lineCapStyle = .round; shaft.stroke()
let head = NSBezierPath(); head.move(to: p(630, 680)); head.line(to: p(740, 680)); head.line(to: p(685, 615)); head.close(); head.fill()

// Text
let para = NSMutableParagraphStyle(); para.lineSpacing = 4
(title as NSString).draw(at: NSPoint(x: 470, y: 250), withAttributes: [
    .font: NSFont.systemFont(ofSize: 76, weight: .bold), .foregroundColor: NSColor.white])
(tagline as NSString).draw(in: NSRect(x: 474, y: 110, width: 520, height: 130), withAttributes: [
    .font: NSFont.systemFont(ofSize: 30, weight: .medium), .foregroundColor: NSColor.white.withAlphaComponent(0.92),
    .paragraphStyle: para])
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
