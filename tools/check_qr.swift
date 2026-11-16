// Decodes the QR code written by web/test.js with macOS's own reader. Usage: swift tools/check_qr.swift /tmp/qr.txt
import AppKit
import CoreImage
let lines = try! String(contentsOfFile: CommandLine.arguments[1], encoding: .utf8).split(separator: "\n").map(String.init)
let want = lines[0], rows = Array(lines.dropFirst()), n = rows.count, s = 8, q = 4, W = (n + 2 * q) * s
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: W, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSColor.white.setFill(); NSRect(x: 0, y: 0, width: W, height: W).fill(); NSColor.black.setFill()
for (y, r) in rows.enumerated() { for (x, c) in r.enumerated() where c == "1" { NSRect(x: (x + q) * s, y: W - (y + q + 1) * s, width: s, height: s).fill() } }
let det = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
let got = (det.features(in: CIImage(bitmapImageRep: rep)!) as! [CIQRCodeFeature]).first?.messageString
print(got == want ? "QR decodes correctly" : "QR MISMATCH: \(got ?? "nil")")
