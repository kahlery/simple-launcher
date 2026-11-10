# SimpleLauncher

Çekmecesiz, iPhone tarzı Android launcher (Kotlin + Jetpack Compose).

- Tüm uygulamalar ana ekranda, dikey kaydırılan tek bir ızgarada (ekranda 7 satır görünür, fazlası aşağıda).
- Uzun bas → ikonlar titrer (düzenleme modu), ikonu parmakla sürükle; diğerleri kayarak yer açar.
- Sürüklerken ekranın üst/alt kenarına gelince liste kendiliğinden kayar.
- Liste en üstteyken herhangi bir yerden aşağı kaydır → bildirim paneli açılır. Home tuşu listeyi en üste döndürür.
- Düzenleme modunda ikonun sol üstündeki "−" → sistem silme onayı açılır; onaylayınca uygulama tamamen silinir ve ana ekrandan kalkar.
- Yeni uygulama yüklenince otomatik olarak son boş yere eklenir.
- Düzenleme modundan çıkmak: boş bir yere dokun, Geri tuşu veya Home tuşu.
- **Klasör:** düzenleme modunda bir uygulamayı başka bir uygulamanın tam ortasına sürükle (hedef büyür), bırak → klasör oluşur ve isim vermek için açılır. Var olan bir klasörün üstüne bırakınca içine eklenir.
- Klasöre dokun → açılır; üstteki isme dokunup değiştirebilirsin. İçinde uzun basıp sürükle → sırala; panelin dışına sürükleyip bırak → klasörden çıkar. Düzenleme modunda klasörün "−" düğmesi klasörü dağıtır (uygulamalar silinmez).
- **Hava durumu şeridi:** en üstte ince, satır genişliğinde; tek renk ikon + yazı (sıcaklık, durum, en yüksek/en düşük, UV indeksi, rüzgâr, şehir). Veri Open-Meteo'dan gelir (anahtar gerekmez), yaklaşık konum kullanılır. Dokununca yenilenir; izin yoksa izin ister.
- **Ezan vakti sayacı:** hava durumu şeridinin altında bir sonraki ezan vakti ve kalan süre (saniye saniye). Vakitler Aladhan'dan Diyanet yöntemiyle (method 13) bugün+yarın için çekilir; gün dönünce ya da konum 5 km'den fazla değişince yeniden çekilir.
- Tüm yazılar JetBrains Mono (SIL OFL lisansı: `licenses/JetBrainsMono-OFL.txt`).
- Yerleşim (sıra ve klasörler) cihazda saklanır.

## Çalıştırma

1. Android Studio'da **Open** ile `SimpleLauncher` klasörünü aç, Gradle sync bitsin.
2. Pixel 9a'da Geliştirici seçenekleri → USB hata ayıklamayı aç, telefonu bağla, **Run**.
3. Telefonda: Ayarlar → Uygulamalar → Varsayılan uygulamalar → **Ana ekran uygulaması** → "Launcher".
   (Home tuşuna basınca da seçim penceresi çıkabilir.)

Eski launcher'a dönmek için aynı yerden Pixel Launcher'ı seç.

## Ayarlar

`HomeScreen.kt` dosyasının başında `COLUMNS` ve `ROWS` ile ızgara boyutunu değiştirebilirsin (şu an 5x7).

## Bilinen eksikler

Widget, ikon paketi, iş profili uygulamaları ve uygulama kısayol menüleri yok.

## Banka bakiyeleri: N26, Revolut, Wise (Enable Banking)

Ezan vaktinin altında her banka için bir satır bakiyeyi Enable Banking üzerinden (PSD2, yalnızca okuma) gösterir.
Bir bankada birden fazla hesap varsa (Revolut/Wise'ta her para birimi ayrı hesaptır) hepsi aynı satırda
yan yana yazılır (`Wise €310.55 · $45.00`); bakiyesi sıfır olan hesaplar gösterilmez.
Dokun → tüm bakiyeleri gizle/göster, uzun bas → o bankayı hemen yenile. Kendiliğinden en fazla 6 saatte bir
yenilenir (PSD2, kullanıcı başında değilken hesap başına günde 4 çekmeye izin verir).

Bir kerelik kurulum:

1. https://enablebanking.com adresinde hesap aç, Control Panel → **Applications** → yeni uygulama:
   - Environment: **Production**
   - Redirect URL: `https://localhost/callback` (açılmaz, launcher yakalar)
   - Anahtarı tarayıcıda oluşturup `.pem` dosyasını indir.
2. Uygulamada **Activate by linking accounts** ile N26, Revolut ve Wise hesaplarını bağla (ücretsiz kısıtlı
   mod yalnızca burada bağlanan hesaplara izin verir).
3. `.pem` dosyasını `secrets/enablebanking.pem` olarak koy, `secrets.properties.example` dosyasını
   `secrets.properties` olarak kopyala ve `ENABLE_BANKING_APP_ID` alanına uygulamanın kimliğini yaz.
   Gösterilecek bankalar `ENABLE_BANKING_ASPSPS` ile seçilir (varsayılan `N26,Revolut,Wise`).
   İkisi de `.gitignore` içinde, repoya girmez.
4. Yeniden derle ve kur; ana ekranda her bankanın satırına dokunup o bankanın onayını ver.

Onay süresi dolunca (en fazla 180 gün) satır "access expired, tap to reconnect" der; dokunup yeniden onayla.
