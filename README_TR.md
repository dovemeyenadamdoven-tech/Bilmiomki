# Altyazı Dublaj (Android)

Kamerayı bilgisayar ekranındaki İngilizce altyazıya tut; uygulama okur, Türkçeye çevirir, Türkçe sesle söyler.
Her şey telefonda çalışır (OCR + çeviri + ses). Internet sadece ilk açılışta çeviri modelini indirmek için gerekli.

## APK'yı bulutta derleme (GitHub, ücretsiz)
1. github.com'da hesap aç, "New repository" ile boş bir depo oluştur (Public olabilir).
2. "uploading an existing file" bağlantısına tıkla, bu zip'i açıp içindeki TÜM dosya ve klasörleri
   (.github, app, build.gradle, settings.gradle, gradle.properties dahil) sürükle bırak, "Commit changes" de.
3. Üstteki "Actions" sekmesine gir, "APK derle" çalışmasının bitmesini bekle (yaklaşık 5-10 dk).
4. Biten çalışmaya tıkla, en altta "Artifacts" bölümünden altyazi-dublaj-apk dosyasını indir, zip'ten çıkar.
5. app-debug.apk dosyasını telefona at, aç, "bilinmeyen kaynaklardan yükleme" iznini ver.

## Kullanım
- İlk açılışta kamera izni ver, çeviri modelinin inmesini bekle ("Hazır" yazısı).
- Yeşil çerçeveyi altyazının üstüne getir (içinden sürükle = taşı, sağ alt köşeden sürükle = boyutlandır).
- Başlat'a bas. "Hız" çubuğu konuşma hızını ayarlar.
- Telefonu stande koy, bilgisayarda altyazı yazısını büyüt, ekran parlaklığını biraz kıs.
