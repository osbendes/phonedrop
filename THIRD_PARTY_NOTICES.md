# Third-party notices

PhoneDrop itself is released under the [MIT License](LICENSE). It includes or uses the following
components, which keep their own licenses.

| Component | Where | License |
|---|---|---|
| [ZXing](https://github.com/zxing/zxing) core 3.5.3 | Android app (draws QR codes) | Apache License 2.0 |
| Google Play services code scanner (ML Kit) | Android app (reads QR codes) | [Google APIs Terms of Service](https://developers.google.com/terms), [ML Kit Terms](https://developers.google.com/ml-kit/terms) |
| Kotlin standard library | Android app | Apache License 2.0 |

Apple's frameworks (SwiftUI, Network, CryptoKit, CoreBluetooth) are part of macOS and are not
redistributed.

The browser page (`web/index.html`) contains no third-party code. Its X25519, SHA-256, HKDF,
ChaCha20-Poly1305 and QR code routines were written for this project from the public specifications
(RFC 7748, FIPS 180-4, RFC 5869, RFC 8439, ISO/IEC 18004) and are tested against Node's
implementations (`web/test.js`).

The full text of the Apache License 2.0 is at <https://www.apache.org/licenses/LICENSE-2.0>.

"AirDrop", "Mac", "macOS" and "iPhone" are trademarks of Apple Inc. "Android" and "Google Play" are
trademarks of Google LLC. "Windows" is a trademark of Microsoft Corporation. PhoneDrop is not
affiliated with or endorsed by any of them.
