#!/bin/bash
# Takes the raw screenshots used in the README and the promo images, with sample content
# ("MacBook" / "Xiaomi", made-up files) in both languages and both themes.
#   tools/screenshots.sh <out-dir> [mac] [phone]
# Mac: needs mac/dist/PhoneDrop.app (run mac/build.sh). Nothing is read from or written to your settings.
# Phone: needs the app installed and paired, USB debugging on, and the phone unlocked. The language and
# theme settings are put back to "System" at the end.
#
# TR: README ve tanıtım görsellerinde kullanılan ham ekran görüntülerini, örnek içerikle ("MacBook" /
# "Xiaomi", uydurma dosyalar) iki dilde ve iki temada alır. Mac'te ayarlarına dokunulmaz; telefonda dil ve
# tema ayarı sonunda "Sistem"e geri alınır.
set -euo pipefail
OUT=${1:?usage: screenshots.sh <out-dir> [mac] [phone]}
shift
WHAT=${*:-mac phone}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$OUT"

if [[ $WHAT == *mac* ]]; then
  for lang in tr en; do
    locale=$([ $lang = tr ] && echo tr_TR || echo en_US)
    for theme in dark light; do
      shot() {  # name, extra args…
        local name=$1; shift
        open -n -W "$ROOT/mac/dist/PhoneDrop.app" --args --demo --lang $lang --theme $theme "$@" \
          --snapshot "$OUT/mac-$name-$lang-$theme.png" -AppleLanguages "($lang)" -AppleLocale $locale
      }
      shot pair --scene pair
      shot ready --scene ready
      shot receiving --scene transfer
      shot sending --scene send
      shot paircode --scene ready --pair
      shot getapp --scene ready --pair-app
    done
  done
fi

if [[ $WHAT == *phone* ]]; then
  APP=com.osbendes.phonedrop/com.phonedrop.app.MainActivity
  PAUSE=${PAUSE:-2}  # seconds to let the screen restart; a slow emulator needs more
  diag() { adb shell am start -n $APP -a com.osbendes.phonedrop.DIAG_"$@" >/dev/null 2>&1; }
  for lang in tr en; do
    for theme in dark light; do
      other=$([ $theme = dark ] && echo light || echo dark)
      shot() {  # name, DEMO extras…
        local name=$1; shift
        diag DEMO "$@"; sleep $PAUSE
        adb exec-out screencap -p > "$OUT/phone-$name-$lang-$theme.png"
      }
      diag LOOK --es lang $lang --es theme $theme; sleep $PAUSE
      shot unpaired --es scene unpaired
      shot ready --es scene ready
      shot sending --es scene sending
      shot receiving --es scene receiving --ez bottom true
      shot settings --es scene ready --es dialog settings
      # Restarting the screen is the only way to close a dialog from here
      diag LOOK --es lang $lang --es theme $other; sleep $PAUSE
      diag LOOK --es lang $lang --es theme $theme; sleep $PAUSE
      shot pairdialog --es scene unpaired --es dialog pair
      diag LOOK --es lang $lang --es theme $other; sleep $PAUSE
    done
  done
  diag LOOK --es lang "" --es theme ""; sleep $PAUSE
  diag DEMO --es scene off
fi
echo "Screenshots are in $OUT"
