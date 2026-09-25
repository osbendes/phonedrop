# PhoneDrop

Android telefondan Mac'e (ve Mac'ten telefona) Wi-Fi üzerinden hızlı ve kesintiye dayanıklı dosya aktarımı.
Telefona uygulama kurmak gerekmez. Ek kütüphane de gerekmez, macOS'taki Python yeterli.

## Başlatma
- **Uygulama olarak:** `PhoneDrop.app`'e çift tıkla (istersen Uygulamalar klasörüne sürükle).
  Kodu değiştirirsen `./build_app.sh` ile uygulamayı yeniden oluştur.
- **Terminalden:** `python3 server.py`

Mac'te bir panel açılır. Telefonla QR kodu okut ve *Mac'e Dosya Gönder*'e dokun.

## Nasıl çalışır
- Dosyalar 8 MB'lık parçalar halinde, 4 paralel bağlantıyla gönderilir.
- Bağlantı koparsa telefon otomatik olarak yeniden dener ve kaldığı yerden devam eder.
- Sayfa kapanırsa aynı dosyayı tekrar seçmen yeterli, gönderilmiş parçalar tekrar gönderilmez.
- Yarım kalan aktarımlar 7 gün saklanır.
- Videonun orijinal tarihi korunur.
- Aktarım sürerken Mac uyku moduna geçmez. Her dosya bitince bildirim gelir.

## Klasörler
- Gelen dosyalar: `~/Downloads/PhoneDrop`
- Mac'ten telefona göndermek için: `~/Downloads/PhoneDrop/Telefona Gönder`

## Ayarlar (ortam değişkenleri)
- `PHONEDROP_PORT` (varsayılan 8765)
- `PHONEDROP_DIR` (kayıt klasörü)

## Güvenlik
QR koddaki gizli anahtar olmadan kimse bağlanamaz. Anahtar `~/.phonedrop/token` dosyasında durur.
Bu dosyayı silersen yeni bir anahtar oluşur ve eski bağlantılar geçersiz olur.
Trafik yerel ağda şifrelenmeden (HTTP) gider. Güvendiğin ağlarda kullan.
