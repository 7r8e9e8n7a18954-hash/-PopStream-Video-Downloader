# Ultra Floating Video Sniffer

एक पूरी तरह काम करने वाली **Android (Kotlin) app** जो:

1. होम स्क्रीन के एक ही बटन **"Start Sniffer & VPN"** से `android.net.VpnService`
   शुरू करती है और डिवाइस का **सारा traffic** userspace relay से forward करती है
   (internet चलता रहता है, साथ-साथ सब कुछ sniff भी होता है)।
2. HTTP traffic से **video URLs live detect** करती है (`.m3u8`, `.mpd`, `.mp4`, `.ts`,
   `videoplayback`…) और HTTPS का **host hint** (TLS ClientHello SNI) निकालती है।
3. **In-app Browser** देती है जो HTTPS (YouTube, Facebook, Instagram, किसी भी site)
   के अंदर तक देख सकती है — WebView के `shouldInterceptRequest` + JS hooks
   (XHR / fetch / `<video>` / Resource-Timing) से **एन्क्रिप्टेड video URLs भी** पकड़ती है।
4. Video मिलते ही स्क्रीन पर सुंदर **floating "Download This Video" bubble**
   (`TYPE_APPLICATION_OVERLAY`, draggable + auto-hide) दिखाती है — tap = download शुरू।
5. HLS (.m3u8 + हज़ारों `.ts` chunks), DASH (.mpd + अलग audio track), fMP4
   segments और normal MP4 — सबको डाउनलोड करके **Mobile-FFmpeg** से एक single
   high-quality **MP4** बनाती है और **गैलरी में save** कर देती है।
6. **कोई admin panel, password या kill-switch नहीं** — पूरी तरह automatic।

---

## 1. Architecture

```
MainActivity ──"Start Sniffer & VPN"──► SnifferVpnService (android.net.VpnService)
   │                                        │
   │ "In-app Browser"                       │  TUN interface (10.111.222.2/32, route 0.0.0.0/0)
   ▼                                        ▼
BrowserActivity (WebView)             IpRelay  (read/inject loop, IPv4)
   │  shouldInterceptRequest            ├── TcpEngine      — terminating TCP relay
   │  JS hooks (XHR/fetch/<video>)      │                    (SYN-ACK, seq/ack, window,
   │  Resource-Timing API               │                     retransmit, RST/FIN — userspace NAT)
   │                                   ├── UdpEngine      — UDP NAT (DNS, QUIC, …)
   │                                   └── TrafficSniffer — HTTP request + Content-Type,
   │                                                        TLS ClientHello SNI, media matcher
   └──────────────┬─────────────────────────────┘
                  ▼
        SnifferRepository (StateFlow) ────► FloatingButtonService
                  │                          (floating "Download This Video" bubble)
                  ▼                                   │ tap
        MainActivity (RecyclerView list)              ▼
                  └──────────────► DownloadService (foreground, dataSync)
                                        │
                                        ▼
                              DownloadEngine (URL type decide करता है)
                              ├── HlsDownloader   : master/variant/audio-rendition + segments
                              ├── DashDownloader  : best video rep + best audio rep
                              └── progressive     : सीधा MP4/WEBM/…
                                        │
                                        ▼
        FfmpegMerger (ffmpeg-kit-full 6.0 LTS, `-c copy`, quality loss 0)
        ├── remuxPlaylist()   : local .m3u8 → single MP4 (TS timestamps fix)
        ├── muxPlaylists()    : video playlist + audio playlist → single MP4
        ├── mux()             : video file + audio file → single MP4
        └── fallback: MediaMerger (MediaExtractor + MediaMuxer, बिना FFmpeg भी काम)
                                        │
                                        ▼
        MediaStoreSaver → Movies/UltraSniffer/…  (गैलरी) + notification
```

### Files (सबसे ज़रूरी)

| File | काम |
|---|---|
| `ui/MainActivity.kt` | एक screen UI: Start/Stop, permission wizard, detected list, share-intent, manual URL |
| `ui/BrowserActivity.kt` | In-app browser — HTTPS media URL capture (WebView + JS hooks) |
| `vpn/SnifferVpnService.kt` | VPN start/stop, TUN establish, foreground service |
| `vpn/IpRelay.kt` | TUN से packet पढ़ना और reply inject करना |
| `vpn/TcpEngine.kt` | userspace TCP: SYN-ACK, sequence/window, retransmit, relay |
| `vpn/UdpEngine.kt` | userspace UDP NAT (DNS/QUIC), optional QUIC block |
| `vpn/TrafficSniffer.kt` | HTTP request/response parse + TLS SNI + media matching |
| `vpn/PacketUtil.kt` | IPv4/TCP/UDP packet build + checksum |
| `overlay/FloatingButtonService.kt` | floating bubble (drag, auto-hide, one-tap download) |
| `download/DownloadService.kt` | foreground queue: download → merge → gallery |
| `download/HlsDownloader.kt` | HLS: variants, audio renditions, chunks, local playlist |
| `download/DashDownloader.kt` | DASH: best video + best audio, init + segments |
| `download/FfmpegMerger.kt` | **Mobile-FFmpeg** calls (remux/mux, stream copy) |
| `download/MediaMerger.kt` | FFmpeg न हो तो MediaExtractor/MediaMuxer fallback |
| `download/MediaStoreSaver.kt` | gallery में save (MediaStore, API 29+) |
| `core/SnifferRepository.kt` | detected media का thread-safe store + events |

---

## 2. Reality check (ज़रूरी बात, छिपाई नहीं गई)

Android पर **HTTPS/TLS और QUIC (HTTP/3) traffic एन्क्रिप्टेड** होता है। कोई भी
non-root VPN app उसके अंदर का पूरा URL नहीं पढ़ सकती — सिर्फ host name (SNI)
दिख सकता है। यह Android की security है, किसी app की कमी नहीं।

इसलिए इस app में **दो तरीके** हैं, और दोनों एक ही list + एक ही floating bubble में
काम करते हैं:

| तरीका | क्या पकड़ता है | कब use करें |
|---|---|---|
| **VPN Sniffer** | वो सारा traffic जो plain HTTP है: बहुत सारी वेबसाइट, CDN, `.m3u8`/`.mpd`/`.ts`/`.mp4` plain links; HTTPS से सिर्फ host hint | किसी भी app/browser में चलने वाला open video traffic |
| **In-app Browser** | HTTPS भी — page जो भी media request करता है (m3u8/mp4/DASH) | YouTube/FB/Instagram जैसी encrypted sites |
| **Share / Paste** | जो link आपके पास है (कोई भी `.m3u8`/`.mpd`/`.mp4`/HLS link) | WhatsApp/Telegram/YouTube share sheet से |

तीनों से मिला URL एक ही download + FFmpeg merge pipeline में जाता है।

---

## 3. APK कैसे build करें और download करें

### तरीका A — GitHub Actions (सबसे आसान, कुछ install नहीं करना)

हर `push` पर workflow `.github/workflows/android-build.yml` चलता है:

1. GitHub पर repo खोलें → **Actions** tab → **Build APK** workflow।
2. **Run workflow** दबाएँ (या कोई भी commit push करें)।
3. Build पूरा होने पर:
   * **Releases → `latest`** में APK files मिलेंगी (सबसे आसान download link), और
   * **Actions → run → Artifacts** में `UltraSniffer-APK-*` zip भी मिलेगी।
4. **`UltraSniffer-arm64-v8a.apk`** (**≈29 MB**) download करें (ज़्यादातर फ़ोनों के लिए
   recommended)। अगर install न हो तो `UltraSniffer-universal.apk` (≈109 MB)
   लें; पुराने 32-bit फ़ोन के लिए `UltraSniffer-armeabi-v7a.apk` (≈30 MB) है।
   ये names हर build में same रहते हैं — link हमेशा latest APK देगा।
   Debug variant (backup के लिए) Actions → Artifacts में मिलता है।

### तरीका B — Android Studio

```
File ▸ Open ▸ (repo folder)
```
Gradle sync होने दें (JDK 17+ चाहिए) → **Run ▶** या
`Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s)`।

### तरीका C — Command line

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/
./gradlew assembleRelease        # APK: app/build/outputs/apk/release/
```

ABI splits की वजह से हर variant में 3 APK बनती हैं:
`…-arm64-v8a-…` (≈30 MB), `…-armeabi-v7a-…` (≈30 MB), `…-universal-…` (≈110 MB)।

### तरीका D — Tests चलाना (parsers की जाँच)

```bash
./gradlew testDebugUnitTest     # HLS + DASH parser के 5 JVM tests
```

---

## 4. फ़ोन में चलाने के steps

1. APK install करें (Settings → "Install unknown apps" allow करें)।
2. App खोलें → **Start Sniffer & VPN** दबाएँ।
3. एक के बाद एक permission देता है (सिर्फ पहली बार):
   * Notification permission (Android 13+)
   * **Display over other apps** (floating button के लिए)
   * **VPN connection request** → *OK*
4. अब status "Sniffer चालू है" दिखेगा और notification आएगी।
5. Video download करने के 3 तरीके:
   * किसी भी app/browser में video चलाएँ → floating **"Download This Video"**
     bubble → tap → download शुरू।
   * App में **🌐 In-app Browser** खोलें → site पर जाएँ → video चलाएँ →
     नीचे/ऊपर दिखने वाला **🎯 Video मिला — tap करें** → download।
   * किसी भी app से link **Share** करके *Ultra Floating Video Sniffer* चुनें,
     या app में **URL paste करें**।
6. Download → merge → gallery (`Movies/UltraSniffer/`) तक का progress notification
   में दिखेगा; पूरा होने पर notification tap करके video चल सकते हैं।

### Settings (MainActivity में)

* **QUIC block करें** — QUIC (UDP/443) block हो जाता है ताकि apps TCP पर आ जाएँ
  और sniffer को ज़्यादा दिखे (कुछ apps इससे slow हो सकते हैं)।
* **HTTPS host hints दिखाएँ** — encrypted traffic से सिर्फ hostname list करता है।

---

## 5. FFmpeg (Mobile-FFmpeg) integration

`app/build.gradle.kts`:

```kotlin
implementation("com.arthenica:ffmpeg-kit-full:6.0-2")
```

ffmpeg-kit को Maven Central से हटा दिया गया है, इसलिए `settings.gradle.kts` में
Google का official Maven-Central mirror जोड़ा गया है (वहाँ AAR अभी भी मौजूद है):

```kotlin
maven {
    url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
    content { includeGroup("com.arthenica") }
}
```

क्या-क्या merge होता है (`download/FfmpegMerger.kt`, सब `-c copy` = re-encode नहीं,
quality 100% वही):

| Case | FFmpeg command (सरल रूप) |
|---|---|
| HLS TS chunks → MP4 | `ffmpeg -allowed_extensions ALL -i video_track.m3u8 -c copy -movflags +faststart final.mp4` |
| HLS + अलग audio rendition | `ffmpeg -i video_track.m3u8 -i audio_track.m3u8 -map 0:v -map 1:a -c copy final.mp4` |
| DASH video + audio (fMP4) | `ffmpeg -i video_track.mp4 -i audio_track.mp4 -map 0:v -map 1:a -c copy final.mp4` |
| `.ts` file जो पहले से merged है | `ffmpeg -i in.ts -map 0:v? -map 0:a? -c copy -movflags +faststart out.mp4` |

डाउनलोडर हर track के chunks की एक **local playlist** लिखता है, इससे FFmpeg
chunks के बीच के timestamp jumps ठीक कर देता है — इसीलिए output हर player
(VLC, MX Player, gallery) में सही चलता है।

अगर FFmpeg किसी stream पर fail हो जाए, तो app अपने-आप
`MediaMerger` (MediaExtractor + MediaMuxer, बिना native binary) पर fall back
कर देती है — download कभी बेकार नहीं जाता।

---

## 6. Troubleshooting

| Problem | Fix |
|---|---|
| Floating bubble नहीं दिख रहा | Settings → Apps → Ultra Sniffer → **Display over other apps** ON करें, फिर sniffer restart करें |
| कुछ detect नहीं हो रहा | वह traffic HTTPS/QUIC है → **In-app Browser** use करें या link **Share/Paste** करें; "QUIC block" भी ON करें |
| Internet slow लगता है | Settings में QUIC block OFF करें (या sniffer बंद करें — बंद करते ही normal VPN-free internet) |
| Download में 403/404 | URL expire हो गया (signed CDN links कुछ देर में बदल जाते हैं) — video दोबारा चलाएँ और नया URL पकड़ें |
| बड़ी video के बाद MP4 में आवाज़/तस्वीर mismatch | वह stream असामान्य है — FFmpeg fail होने पर app MediaMerger से remux करती है; दोबारा try करें या `.ts` file cache से save हो जाती है |
| App install नहीं हो रही | पुरानी install uninstall करें (signature अलग), फिर install करें |

---

## 7. Build configuration

| | |
|---|---|
| Language | Kotlin 1.9.24 |
| AGP / Gradle | 8.5.2 / 8.7 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 (Android 8.0+) |
| UI | Material 3 (View system + ViewBinding), RecyclerView |
| Networking | OkHttp 4.12 |
| Media | ffmpeg-kit-full 6.0-2 (+ MediaMuxer fallback) |
| Tests | JUnit4 — HLS/DASH parser tests (`./gradlew testDebugUnitTest`, CI में भी चलते हैं) |
| Signing | demo keystore committed (`app/keystore/ultra-sniffer.keystore`) ताकि हर CI build installable + updatable हो |

---

## 8. कानूनी नोट

सिर्फ वो content download करें जिसका आपको अधिकार है (अपनी videos, CC/Public
Domain, या जिसकी permission हो)। YouTube/Netflix/Prime जैसी DRM-protected
services का content इस app से download नहीं होता और उनकी ToS का उल्लंघन मत करें।
