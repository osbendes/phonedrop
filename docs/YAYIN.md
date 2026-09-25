# PhoneDrop yayın kontrol listesi

Sırayla ilerle. [x] olanlar tamamlandı.

## Hazır olanlar
- [x] Şifreli protokol v2 (AES-256-GCM, QR ile eşleştirme): `docs/PROTOCOL.md`
- [x] Mac uygulaması: Swift menü çubuğu uygulaması, universal (Apple Silicon + Intel), DMG: `mac/build.sh`
- [x] Android uygulaması v2:
  - Şifreli protokol
  - Uygulama içi QR okuyucu
  - İki yönlü aktarım
  - Türkçe/İngilizce
  - Play kurallarına uygun izinler
- [x] Otomatik testler: Kotlin istemcisi gerçek Mac uygulamasına karşı test ediliyor (6 test + 1 GB yük testi)
- [x] Gizlilik politikası (`docs/privacy.html`), mağaza metinleri ve görselleri (`docs/store/`)

## 0. GitHub'da yayın (şimdilik tek kanal)
Yerel git deposu ve ilk commit hazır. Sürüm dosyaları `release/` klasöründe (git'e eklenmez).
1. https://github.com/new adresinde **phonedrop** adında herkese açık bir depo oluştur. README veya lisans ekleme, depo boş kalsın.
2. Terminalde gönder:
   `cd ~/Developer/PhoneDrop && git push -u origin main`
3. **Releases › Draft a new release**:
   - Tag: `v1.0.0-beta.1`
   - Başlık: `PhoneDrop 1.0.0 beta 1`
   - Açıklama: `release/NOTES.md` dosyasının içeriği
   - Eklenecek dosyalar: `PhoneDrop.dmg`, `PhoneDrop.apk`, `SHA256SUMS.txt`
   - "Pre-release" kutusunu **işaretleme**. İşaretlersen sitedeki indirme bağlantıları çalışmaz.
4. **Settings › Pages**: Source = "Deploy from a branch", Branch = `main`, klasör = `/docs`. Site adresi https://osbendes.github.io/phonedrop/ olur.

**İmza anahtarı:** Android anahtarı `~/Developer/PhoneDrop-signing/` klasöründe. Bu klasörü güvenli bir yere yedekle (örneğin şifreli bir disk ya da şifre yöneticisi). Anahtar kaybolursa kullanıcılar sonraki sürümleri eskisinin üzerine kuramaz.

## 1. Gerçek telefonda test
Xiaomi, USB üzerinden uygulama kurulumunu engelliyor. Şu iki ayarı aç, gerisini ben yapayım:

**Ayarlar › Ek ayarlar › Geliştirici seçenekleri**
- **USB üzerinden yükle**: Açık. Mi hesabıyla giriş ve SIM kart gerekebilir.
- **USB hata ayıklama (Güvenlik ayarları)**: Açık.

Bu ayarlarla uygulamayı telefona kurup tüm akışı test edebilirim. Play Store için gereken ekran görüntülerini de alabilirim.

## 2. Google Play Console hesabı
1. https://play.google.com/console adresinden geliştirici hesabı aç (tek seferlik 25 $).
2. Kimlik doğrulamasını tamamla. Bu birkaç gün sürebilir.
3. **Kişisel hesaplarda:** yayına çıkmadan önce en az 12 test kullanıcısıyla 14 gün kapalı test yapılması zorunlu.

## 3. İmzalama (hazır)
Anahtar oluşturuldu: `~/Developer/PhoneDrop-signing/`. `./gradlew bundleRelease` komutu Play'e yüklenecek `.aab` dosyasını bu anahtarla imzalar. Play'e geçerken Play App Signing'de "kendi anahtarını kullan" seçeneğini seçersen, GitHub'dan APK kuranlar Play sürümüne de kaldırmadan geçebilir.

## 4. Bilgiler (hazır)
- Gizlilik politikası adresi: https://osbendes.github.io/phonedrop/privacy.html
- İletişim: osbendes@gmail.com
- Paket adı: `com.osbendes.phonedrop`. Play'e ilk yüklemeden sonra değiştirilemez.

## 5. Play Console formları
- **Gizlilik politikası:** GitHub Pages adresi.
- **Reklamlar:** Hayır.
- **Uygulama erişimi:** Uygulama bir Mac uygulamasıyla birlikte çalışıyor. İnceleme ekibi için Mac uygulamasının indirme bağlantısını ve kısa bir kullanım açıklaması yaz. Bu bölüm önemli: inceleyen kişi uygulamayı deneyemezse uygulama reddedilebilir.
- **Veri güvenliği:**
  - Geliştirici veri toplamıyor veya paylaşmıyor.
  - Aktarılan veriler şifreleniyor.
  - QR okuyucu Google Play hizmetleri (ML Kit) üzerinden çalışıyor. Google'ın ML Kit veri açıklama sayfasını kontrol edip bu formu ona göre doldur.
- **Ön plan hizmeti beyanı:**
  - Tür: `dataSync`.
  - Açıklama: "Kullanıcının seçtiği dosyaları yerel ağdaki Mac'ine aktarır."
  - Kısa bir ekran kaydı istenir: bir videoyu paylaş, bildirimde ilerlemeyi göster.
- **İçerik derecelendirmesi:** Araçlar/Yardımcı program, sakıncalı içerik yok.
- **Hedef kitle:** 13+ (çocuklara yönelik değil).
- **Mağaza kaydı:** `docs/store/listing.md` + görseller + en az 2 telefon ekran görüntüsü.

## Bilinen sınırlamalar
- Mac uygulaması imzasız. Kullanıcılar ilk açılışta Sistem Ayarları › Gizlilik ve Güvenlik bölümünden **Yine de Aç** demek zorunda. İleride Apple Developer ID (yıllık 99 $) alınırsa bu adım ortadan kalkar.
- Android 15 ve sonrasında arka plan aktarımı günde 6 saatle sınırlı. Normal kullanımda bu sınıra ulaşılmaz.
- Eşleştirme anahtarı Mac başına tek. Bir telefonu kaldırmak için Mac'te eşleştirmeyi sıfırlamak gerekiyor, bu da tüm telefonların yeniden eşleşmesini gerektiriyor.
