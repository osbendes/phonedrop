# PhoneDrop architecture

**English** · [Türkçe](#türkçe)

## Principles

1. **The phone app is enough.** A computer needs nothing installed: any browser can send and receive.
   An installed desktop app is an option for people who transfer often, never a requirement.
2. **Local only.** Files go straight from one device to the other over Wi-Fi (or the phone's own
   hotspot). There is no server, account, cloud relay or analytics.
3. **Encrypted between the two ends.** Keys are exchanged by scanning a QR code, never typed and
   never sent over the network in the clear.
4. **Small.** No UI frameworks or bundled runtimes; each app uses what its platform already has.

## Who talks to whom

| Phone | Computer | How | State |
|---|---|---|---|
| Android | Mac with the app | Paired link, protocol v2 (AES-256-GCM, 4 parallel connections, resumable, works without a router) | Shipped |
| Android | Any computer's browser (Windows, Mac, Linux, ChromeOS) | Web session, protocol v3 (X25519 + ChaCha20-Poly1305) | In 1.5 |
| Android | Another phone's browser (iPhone, Android) | Same web session; the other phone opens the page | In 1.5, basic |
| iPhone | Any browser | Same web session, served by an iPhone app | Planned |
| iPhone | Mac with the app | Protocol v2 client in the iPhone app | Planned |
| Android / iPhone | Windows with an app | Protocol v2 server in a Windows app | Planned |

The two protocols are specified in [PROTOCOL.md](PROTOCOL.md).

### Paired link (installed apps)

The desktop app is the server; the phone is the client. Pairing shares a 256-bit key once, by QR
code. Because both ends are installed software holding that key, an attacker on the network can
neither read nor change anything. This is the fastest path and the one that survives sleep, dropped
connections and multi-gigabyte files, and it can run with no router through Bluetooth plus the
phone's hotspot.

### Web session (nothing installed)

The phone is the server; a browser page is the client. The phone shows an address
(`192.168.1.23:8765`), the page shows a QR code holding a one-time public key, and the phone scans
it. Both sides derive a session key (X25519, HKDF-SHA256) and every file then travels in
ChaCha20-Poly1305 boxes. The key never crosses the network, and only the browser whose code was
scanned can talk to the phone.

The single page (`web/index.html`) is shared by every phone app. It has no dependencies and carries
its own cryptography, because browsers disable their built-in crypto API on plain-HTTP pages.

**Limits, stated plainly:**

- The page itself arrives over plain HTTP, so someone who can *alter* traffic on the network (not
  just listen) could replace it. Passive eavesdropping is defeated; an active attacker on the same
  network is not. Use a network you trust, or the phone's own hotspot, where the phone and the
  computer are the only two devices. The paired link has no such limit.
- A browser keeps a downloaded file in its own temporary storage until it is complete, so files of
  several gigabytes depend on the browser. The installed app has no such limit.
- The phone app must stay open or in the background with its notification; the session ends after
  15 idle minutes.

## Code layout

| Path | What |
|---|---|
| `web/` | The browser page and its tests |
| `android/` | Android app (Kotlin, no UI libraries). `core/` is pure JVM and unit-tested: protocol v2 client, web host, crypto |
| `mac/` | Mac app (Swift, SwiftPM): protocol v2 server, direct mode |
| `docs/` | Protocol, architecture, privacy policy, download page, store material |
| `tools/` | Icon, screenshot and test helpers |

Planned: `ios/` (SwiftUI app that reuses the Mac app's crypto and HTTP code and serves `web/`), and
`windows/` (a small receiver that implements the protocol v2 server).

## Distribution

| Platform | Channel | What it needs |
|---|---|---|
| Android | APK on GitHub Releases (now); Google Play (bundle is built) | Play Console account (one-time US$25) and identity verification |
| macOS | DMG on GitHub Releases, ad-hoc signed (now) | Apple Developer Program (US$99/year) to sign with a Developer ID and notarize, which removes the "can't verify" warning |
| iPhone | App Store / TestFlight only | The same Apple Developer Program membership |
| Windows | Works today through the browser; installable app planned | To avoid the SmartScreen warning: Microsoft Store listing or a code-signing certificate |
| Any computer | The browser page served by the phone | Nothing |

Every push is built and tested by GitHub Actions (`.github/workflows/build.yml`): Android unit tests
and release build, the Mac app, and the browser page's crypto tests.

## Licensing

PhoneDrop is MIT-licensed. Third-party components and trademarks are listed in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and shown in each app under Settings › About.

---

## Türkçe

[English](#phonedrop-architecture) · **Türkçe**

### İlkeler

1. **Telefon uygulaması yeterlidir.** Bilgisayara hiçbir şey kurmak gerekmez: herhangi bir tarayıcı
   dosya gönderip alabilir. Masaüstü uygulaması, sık aktarım yapanlar için bir seçenektir; zorunlu
   değildir.
2. **Yalnızca yerel ağ.** Dosyalar Wi-Fi (ya da telefonun kendi hotspot'u) üzerinden bir cihazdan
   diğerine doğrudan gider. Sunucu, hesap, bulut aktarımı ya da analiz yoktur.
3. **İki uç arasında şifreli.** Anahtarlar karekod okutularak paylaşılır; elle yazılmaz ve ağ
   üzerinden açık halde gönderilmez.
4. **Küçük.** Arayüz kütüphanesi ya da gömülü çalışma ortamı yoktur; her uygulama kendi
   platformunda hazır olanı kullanır.

### Kim kiminle konuşur

| Telefon | Bilgisayar | Nasıl | Durum |
|---|---|---|---|
| Android | Uygulama kurulu Mac | Eşleşmiş bağlantı, protokol v2 (AES-256-GCM, 4 paralel bağlantı, kaldığı yerden devam, modemsiz çalışır) | Yayında |
| Android | Herhangi bir bilgisayarın tarayıcısı (Windows, Mac, Linux, ChromeOS) | Web oturumu, protokol v3 (X25519 + ChaCha20-Poly1305) | 1.5'te |
| Android | Başka bir telefonun tarayıcısı (iPhone, Android) | Aynı web oturumu; diğer telefon sayfayı açar | 1.5'te, temel |
| iPhone | Herhangi bir tarayıcı | Aynı web oturumu; iPhone uygulaması sunar | Planlandı |
| iPhone | Uygulama kurulu Mac | iPhone uygulamasında protokol v2 istemcisi | Planlandı |
| Android / iPhone | Uygulama kurulu Windows | Windows uygulamasında protokol v2 sunucusu | Planlandı |

İki protokol [PROTOCOL.md](PROTOCOL.md) içinde tanımlıdır.

### Eşleşmiş bağlantı (kurulu uygulamalar)

Masaüstü uygulaması sunucu, telefon istemcidir. Eşleştirme, 256 bitlik bir anahtarı karekodla bir
kez paylaşır. İki uç da bu anahtarı tutan kurulu yazılım olduğu için ağdaki bir saldırgan hiçbir
şeyi okuyamaz ve değiştiremez. En hızlı yol budur; uyku, kopan bağlantı ve gigabaytlarca dosyaya
dayanır, Bluetooth ve telefonun hotspot'u sayesinde modem olmadan da çalışır.

### Web oturumu (hiçbir şey kurmadan)

Telefon sunucu, tarayıcıdaki sayfa istemcidir. Telefon bir adres gösterir (`192.168.1.23:8765`),
sayfa tek kullanımlık bir açık anahtar içeren karekod gösterir, telefon bu kodu okutur. İki taraf
bir oturum anahtarı türetir (X25519, HKDF-SHA256) ve bütün dosyalar ChaCha20-Poly1305 kutuları
içinde gider. Anahtar ağdan hiç geçmez; telefonla yalnızca kodu okutulan tarayıcı konuşabilir.

Tek sayfa (`web/index.html`) bütün telefon uygulamalarında ortaktır. Bağımlılığı yoktur ve kendi
şifreleme kodunu taşır, çünkü tarayıcılar düz HTTP sayfalarında yerleşik şifreleme arayüzünü kapatır.

**Sınırlar, açıkça:**

- Sayfanın kendisi düz HTTP ile gelir; bu yüzden ağdaki trafiği yalnızca dinlemekle kalmayıp
  *değiştirebilen* biri sayfayı değiştirebilir. Pasif dinleme engellenir; aynı ağdaki aktif bir
  saldırgan engellenmez. Güvendiğin bir ağı ya da telefonun kendi hotspot'unu kullan; orada ağda
  yalnızca telefon ve bilgisayar vardır. Eşleşmiş bağlantıda böyle bir sınır yoktur.
- Tarayıcı, indirilen dosyayı tamamlanana kadar kendi geçici alanında tutar; birkaç gigabaytlık
  dosyalar tarayıcıya bağlıdır. Kurulu uygulamada böyle bir sınır yoktur.
- Telefon uygulaması açık ya da bildirimiyle arka planda kalmalıdır; oturum 15 dakika boş kalınca
  kapanır.

### Dağıtım

| Platform | Kanal | Gerekenler |
|---|---|---|
| Android | GitHub Releases'te APK (şimdi); Google Play (paket hazır) | Play Console hesabı (bir kereye mahsus 25 ABD doları) ve kimlik doğrulama |
| macOS | GitHub Releases'te DMG, geçici imzalı (şimdi) | Developer ID ile imzalayıp noter onayı almak için Apple Developer Program (yılda 99 ABD doları); "doğrulanamıyor" uyarısını kaldırır |
| iPhone | Yalnızca App Store / TestFlight | Aynı Apple Developer Program üyeliği |
| Windows | Bugün tarayıcıyla çalışır; kurulabilir uygulama planlandı | SmartScreen uyarısından kaçınmak için Microsoft Store kaydı ya da kod imzalama sertifikası |
| Her bilgisayar | Telefonun sunduğu tarayıcı sayfası | Hiçbir şey |

Her gönderim GitHub Actions ile derlenir ve test edilir (`.github/workflows/build.yml`).

### Lisans

PhoneDrop MIT lisanslıdır. Üçüncü taraf bileşenler ve ticari markalar
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) dosyasında listelenir ve her uygulamada
Ayarlar › Hakkında altında gösterilir.
