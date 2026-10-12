# PhoneDrop

AirDrop-style file transfer from Android to Mac (and back) over your local Wi-Fi.

**[Download](https://osbendes.github.io/phonedrop/)**: Mac app (macOS 13+) and Android app (Android 10+).

- **android/**: Android app (Kotlin, no UI libraries). Share sheet target, background transfers, in-app QR pairing.
- **mac/**: macOS menu bar app (Swift/SwiftUI, no dependencies). Receives files, advertises itself over Bonjour.
- **docs/PROTOCOL.md**: the wire protocol. Every request is authenticated, and every body is encrypted with AES-256-GCM.
- **docs/privacy.html**: privacy policy. **docs/store/**: Play Store texts and graphics.
- **legacy/**: the original browser-based prototype (Python), kept for reference.

## Build

### Mac app (macOS 13+, Command Line Tools are enough)

```bash
mac/build.sh
```

This produces `mac/dist/PhoneDrop.app` (universal, ad-hoc signed) and `mac/dist/PhoneDrop.dmg`.

### Android app

```bash
cd android
./gradlew bundleRelease       # Play Store upload (app/build/outputs/bundle/release/app-release.aab)
./gradlew assembleRelease     # APK for sideloading
```

Release signing reads `android/keystore.properties`:

```
storeFile=/absolute/path/to/upload-key.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Without that file, release builds use the debug key.

## Tests

`android/app/src/test/.../MacIntegrationTest.kt` runs the Kotlin client against a running Mac app, which checks both
implementations of the protocol against each other. To run it, start `PhoneDrop.app` and create `/tmp/phonedrop-test.json`:

```json
{"base": "http://127.0.0.1:8765", "saveDir": "<download folder>",
 "outboxId": "<id>", "outboxPath": "<file in outbox>", "outboxId2": "<id>", "outboxPath2": "<another file>"}
```

Then run `./gradlew testDebugUnitTest`. The 1 GiB load test only runs when `/tmp/phonedrop-load` exists.

## Installing the unsigned Mac app

The Mac app is not notarized, so on first launch macOS blocks it. To open it:

1. Open System Settings › Privacy & Security.
2. Next to the message about PhoneDrop, click **Open Anyway**.
3. When asked, allow incoming network connections.
