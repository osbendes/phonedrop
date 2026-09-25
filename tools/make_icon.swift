// Draws the PhoneDrop icon. Usage: swift make_icon.swift <out.png> <size> [full-bleed]
import AppKit

let args = CommandLine.arguments
let out = args[1]
let size = CGFloat(Double(args[2])!)
let fullBleed = args.count > 3   // Play Store icon: no transparent margin

let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size), pixelsHigh: Int(size), bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                           bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let s = size / 1024
let inset: CGFloat = fullBleed ? 0 : 100 * s
let rect = NSRect(x: inset, y: inset, width: size - 2 * inset, height: size - 2 * inset)
let bg = NSBezierPath(roundedRect: rect, xRadius: fullBleed ? 0 : 185 * s, yRadius: fullBleed ? 0 : 185 * s)
NSGradient(starting: NSColor(red: 0.26, green: 0.52, blue: 1.0, alpha: 1),
           ending: NSColor(red: 0.11, green: 0.30, blue: 0.85, alpha: 1))!.draw(in: bg, angle: -90)

NSColor.white.setFill()
NSColor.white.setStroke()
// Phone (left)
let phone = NSBezierPath(roundedRect: NSRect(x: 250 * s, y: 330 * s, width: 190 * s, height: 360 * s), xRadius: 34 * s, yRadius: 34 * s)
phone.lineWidth = 26 * s
phone.stroke()
NSBezierPath(roundedRect: NSRect(x: 315 * s, y: 362 * s, width: 60 * s, height: 14 * s), xRadius: 7 * s, yRadius: 7 * s).fill()
// Laptop (right)
let screen = NSBezierPath(roundedRect: NSRect(x: 560 * s, y: 420 * s, width: 250 * s, height: 175 * s), xRadius: 18 * s, yRadius: 18 * s)
screen.lineWidth = 26 * s
screen.stroke()
NSBezierPath(roundedRect: NSRect(x: 520 * s, y: 360 * s, width: 330 * s, height: 30 * s), xRadius: 15 * s, yRadius: 15 * s).fill()
// Arrow: from above the phone, arcing down into the laptop screen
let shaft = NSBezierPath()
shaft.move(to: NSPoint(x: 345 * s, y: 735 * s))
shaft.curve(to: NSPoint(x: 685 * s, y: 672 * s), controlPoint1: NSPoint(x: 390 * s, y: 880 * s), controlPoint2: NSPoint(x: 660 * s, y: 880 * s))
shaft.lineWidth = 30 * s
shaft.lineCapStyle = .round
shaft.stroke()
let head = NSBezierPath()
head.move(to: NSPoint(x: 630 * s, y: 680 * s))
head.line(to: NSPoint(x: 740 * s, y: 680 * s))
head.line(to: NSPoint(x: 685 * s, y: 615 * s))
head.close()
head.fill()
NSGraphicsContext.restoreGraphicsState()

try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
