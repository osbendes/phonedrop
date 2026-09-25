#!/bin/bash
# PhoneDrop.app olusturur (cift tiklayarak acilabilen Mac uygulamasi)
set -e
cd "$(dirname "$0")"
# Android uygulamasini derle (Android Studio kuruluysa)
if [ -d ../android ] && [ -d "/Applications/Android Studio.app" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  (cd ../android && ./gradlew assembleRelease -q) && cp ../android/app/build/outputs/apk/release/app-release.apk PhoneDrop.apk
fi
APP="PhoneDrop.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp server.py "$APP/Contents/Resources/"
cp -R web "$APP/Contents/Resources/"
[ -f PhoneDrop.apk ] && cp PhoneDrop.apk "$APP/Contents/Resources/"
cat > "$APP/Contents/MacOS/PhoneDrop" <<'SH'
#!/bin/bash
DIR="$(cd "$(dirname "$0")/../Resources" && pwd)"
mkdir -p "$HOME/.phonedrop"
exec /usr/bin/python3 "$DIR/server.py" >> "$HOME/.phonedrop/log.txt" 2>&1
SH
chmod +x "$APP/Contents/MacOS/PhoneDrop"
cat > "$APP/Contents/Info.plist" <<'PL'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>PhoneDrop</string>
  <key>CFBundleDisplayName</key><string>PhoneDrop</string>
  <key>CFBundleIdentifier</key><string>local.phonedrop</string>
  <key>CFBundleVersion</key><string>1.0</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleExecutable</key><string>PhoneDrop</string>
  <key>LSUIElement</key><true/>
</dict></plist>
PL
echo "Hazir: $(pwd)/$APP  ->  Uygulamalar klasorune surukleyebilirsin."
