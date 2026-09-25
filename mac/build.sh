#!/bin/bash
# Builds dist/PhoneDrop.app and dist/PhoneDrop.dmg (universal: Apple Silicon + Intel).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(pwd)
DIST="$ROOT/dist"
APP="$DIST/PhoneDrop.app"
rm -rf "$DIST"
mkdir -p "$DIST"

# Both triples write to the same products folder, so copy each binary right after its build
ARM="$DIST/PhoneDrop-arm64"
X86="$DIST/PhoneDrop-x86_64"
swift build -c release --triple arm64-apple-macosx13.0
cp "$(swift build -c release --triple arm64-apple-macosx13.0 --show-bin-path)/PhoneDrop" "$ARM"
swift build -c release --triple x86_64-apple-macosx13.0
cp "$(swift build -c release --triple x86_64-apple-macosx13.0 --show-bin-path)/PhoneDrop" "$X86"

mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
lipo -create "$ARM" "$X86" -output "$APP/Contents/MacOS/PhoneDrop"
rm -f "$ARM" "$X86"
cp Resources/Info.plist "$APP/Contents/"
cp -R Resources/en.lproj Resources/tr.lproj "$APP/Contents/Resources/"

# Icon
ICONSET="$DIST/AppIcon.iconset"
mkdir -p "$ICONSET"
for sz in 16 32 128 256 512; do
  swift ../tools/make_icon.swift "$ICONSET/icon_${sz}x${sz}.png" $sz
  swift ../tools/make_icon.swift "$ICONSET/icon_${sz}x${sz}@2x.png" $((sz * 2))
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf "$ICONSET"

# Ad-hoc signature (no Apple Developer ID)
codesign --force --deep --sign - "$APP"

# DMG with an Applications shortcut
STAGE="$DIST/dmg"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
hdiutil create -volname PhoneDrop -srcfolder "$STAGE" -ov -format UDZO "$DIST/PhoneDrop.dmg" >/dev/null
rm -rf "$STAGE"
echo "Built $APP and $DIST/PhoneDrop.dmg"
