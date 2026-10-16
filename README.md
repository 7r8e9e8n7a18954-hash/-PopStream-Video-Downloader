# Ultra Floating Video Sniffer

## ⬇️ APK download (सीधे फ़ोन में install करने के लिए)

हर commit पर GitHub Actions खुद APK बनाता है और यहाँ publish कर देता है:

**➡️ https://github.com/7r8e9e8n7a18954-hash/-PopStream-Video-Downloader/releases/tag/latest**

| फ़ाइल | Size | किसके लिए |
|---|---|---|
| `UltraSniffer.apk` — **बस यही एक file है** | ≈30 MB | हर फ़ोन (arm64 + armv7 दोनों एक ही APK में) |

Release page पर सिर्फ़ एक ही APK रहती है — कौन-सी download करें, यह सोचना नहीं पड़ता।
(छोटी per-ABI APKs चाहिए तो Actions → run → Artifacts में मिल जाती हैं।)

Install: APK खोलें → *Install unknown apps* allow करें → Install → app खोलें →
**Start Sniffer & VPN** → VPN + overlay permission दें। बस।

---

एक पूरी तरह काम करने वाली **Android (Kotlin) app** जो:

1. होम स्क्रीन के एक ही बटन **"Start Sniffer & VPN"** से `android.net.VpnService`
   शुरू करती है और डिवाइस का **सारा traffic** userspace relay से forward करती है
   (internet चलता रहता है, साथ-साथ सब कुछ sniff भी होता है)।
2. HTTP traffic से **video URLs live detect** करती है (`.m3u8`, `.mpd`, `.mp4`, `.ts`,
   `videoplayback`…) और HTTPS का **host hint** (TLS ClientHello SNI) निकालती है।
3. **In-app Browser** देती है जो HTTPS (YouTube, Facebook, Instagram, किसी भी site)
   के अंदर तक देख सकती है — WebView के `shouldInterceptRequest` + JS hooks
   (XHR / fetch / `<video>` / Resource-Timing) से **एन्क्रिप्टेड video URLs भी** पकड़ती है।
4. **YouTube link paste/share करें → असली quality options** (1080p तक) निकलती हैं —
   app YouTube के InnerTube API से video + audio tracks लाता है और FFmpeg से
   merge करके एक high-quality MP4 बनाता है।
5. **किसी भी website का link paste करें** → app page के HTML के अंदर से
   video (`.mp4`/`.m3u8`/`.mpd`, `og:video`, embedded JSON) खुद ढूँढ लेता है;
   page के अंदर embedded YouTube player हो तो उसे भी resolve करता है।
6. Video मिलते ही स्क्रीन पर सुंदर **floating "Download This Video" bubble**
   (`TYPE_APPLICATION_OVERLAY`, draggable + auto-hide) दिखाती है — tap = download शुरू।
   YouTube/FB जैसी sites के host hint पर भी bubble आता है जो सीधे in-app browser खोलता है।
7. HLS (.m3u8 + हज़ारों `.ts` chunks), DASH (.mpd + अलग audio track), fMP4
   segments और normal MP4 — सबको डाउनलोड करके **Mobile-FFmpeg** से एक single
   high-quality **MP4** बनाती है और **गैलरी में save** कर देती है।
8. **कोई admin panel, password या kill-switch नहीं** — कोई link मना नहीं होता,
   app कुछ भी block नहीं करती; पूरी तरह automatic।

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
   │  🎬 "इस page की video ढूँढो"        ├── UdpEngine      — UDP NAT (DNS, QUIC, …)
   │                                   └── TrafficSniffer — HTTP request + Content-Type,
   │                                                        TLS ClientHello SNI, media matcher
   └──────────────┬─────────────────────────────┘
                  ▼
        SnifferRepository (StateFlow) ────► FloatingButtonService
                  │                          (floating "Download This Video" bubble;
                  │                           host hint → in-app browser)
                  ▼
   MainActivity / BrowserActivity / Share-intent / Floating bubble
                  │  paste / share / sniffed link  ("कोई भी link")
                  ▼
        MediaResolver ── "कोई भी link → असली video options"
        ├── YouTubeExtractor    : InnerTube player API, Android VR/iOS/Android/Web clients
        │                         → video+audio tracks (1080p तक), ciphered → साफ़ error
        ├── PageMediaExtractor  : page HTML/JSON से .mp4/.m3u8/.mpd, og:video, JSON keys
        └── Direct              : URL खुद playlist/file है → सीधे download
                  │  quality chooser
                  ▼
        DownloadService (foreground, dataSync)
                  │
                  ▼
        DownloadEngine (URL type decide करता है)
        ├── HlsDownloader   : master/variant/audio-rendition + segments
        ├── DashDownloader  : best video rep + best audio rep
        ├── pair()          : video-only + audio-only tracks (YouTube/DASH) → mux
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
| `ui/BrowserActivity.kt` | In-app browser — HTTPS media URL capture (WebView + JS hooks) + "page की video ढूँढो" |
| `ui/ResolverFlow.kt` | Resolve progress dialog, quality chooser, "download anyway" fallback |
| `download/MediaResolver.kt` | **कोई भी link → download options** (YouTube / page / direct) |
| `download/YouTubeExtractor.kt` | InnerTube player API (4 client profiles), format pairing, friendly errors |
| `download/PageMediaExtractor.kt` | Page HTML/JSON में media URLs ढूँढना (pure + unit-tested) |
| `download/ResolvedStream.kt` | एक download option (url + audioUrl + size + quality) |
| `vpn/SnifferVpnService.kt` | VPN start/stop, TUN establish, foreground service |
| `vpn/IpRelay.kt` | TUN से packet पढ़ना और reply inject करना |
| `vpn/TcpEngine.kt` | userspace TCP: SYN-ACK, sequence/window, retransmit, relay |
| `vpn/UdpEngine.kt` | userspace UDP NAT (DNS/QUIC), optional QUIC block |
| `vpn/TrafficSniffer.kt` | HTTP request/response parse + TLS SNI + media matching |
| `vpn/PacketUtil.kt` | IPv4/TCP/UDP packet build + checksum |
| `overlay/FloatingButtonService.kt` | floating bubble (drag, auto-hide, one-tap download / browser) |
| `download/DownloadService.kt` | foreground queue: download → merge → gallery (video+audio pairs भी) |
| `download/HlsDownloader.kt` | HLS: variants, audio renditions, chunks, local playlist |
| `download/DashDownloader.kt` | DASH: best video + best audio, init + segments |
| `download/FfmpegMerger.kt` | **Mobile-FFmpeg** calls (remux/mux, stream copy) |
| `download/MediaMerger.kt` | FFmpeg न हो तो MediaExtractor/MediaMuxer fallback |
| `download/MediaStoreSaver.kt` | gallery में save (MediaStore, API 29+) |
| `core/SnifferRepository.kt` | detected media का thread-safe store + events (duplicate dedupe) |

---

## 2. Reality check (ज़रूरी बात, छिपाई नहीं गई)

Android पर **HTTPS/TLS और QUIC (HTTP/3) traffic एन्क्रिप्टेड** होता है। कोई भी
non-root VPN app उसके अंदर का पूरा URL नहीं पढ़ सकती — सिर्फ host name (SNI)
दिख सकता है। यह Android की security है, किसी app की कमी नहीं।

इसलिए इस app में **चार तरीके** हैं, और सब एक ही list + एक ही bubble + एक ही
download pipeline में जाते हैं:

| तरीका | क्या पकड़ता है | कब use करें |
|---|---|---|
| **VPN Sniffer** | वो सारा traffic जो plain HTTP है: बहुत सारी वेबसाइट, CDN, `.m3u8`/`.mpd`/`.ts`/`.mp4` plain links; HTTPS से host hint (+bubble जो browser खोलता है) | किसी भी app/browser में चलने वाला open video traffic |
| **In-app Browser** | HTTPS भी — page जो भी media request करता है (m3u8/mp4/DASH) + **"🎬 इस page की video ढूँढो"** बटन | YouTube/FB/Instagram जैसी encrypted sites |
| **YouTube link** | YouTube का असली player API — 1080p तक video+audio tracks, FFmpeg merge | YouTube का कोई भी link (Share/Paste) |
| **किसी भी site का link** | Page के HTML/JSON से `.mp4`/`.m3u8`/`.mpd`, `og:video`, embedded players | कोई भी web page link |

> **छिपाई हुई सच्चाई:** non-root Android पर HTTPS payload किसी भी app के लिए
> पढ़ना असंभव है — यह Android की security है। इसलिए जिन sites पर तरीका 1 काम न
> करे, वहाँ तरीका 2/3/4 से वही video मिलती है। App कुछ block नहीं करती; जहाँ
> direct URL नहीं मिलता वहाँ "फिर भी download करो" और in-app browser दोनों
> available रहते हैं।

---

## 3. APK कैसे build करें और download करें

### तरीका A — GitHub Actions (सबसे आसान, कुछ install नहीं करना)

हर `push` पर workflow `.github/workflows/android-build.yml` चलता है:

1. GitHub पर repo खोलें → **Actions** tab → **Build APK** workflow।
2. **Run workflow** दबाएँ (या कोई भी commit push करें)।
3. Build पूरा होने पर:
   * **Releases → `latest`** में APK files मिलेंगी (सबसे आसान download link), और
   * **Actions → run → Artifacts** में `UltraSniffer-APK-*` zip भी मिलेगी।
4. **`UltraSniffer.apk`** (~30 MB) download करें — यही एक file है, हर फ़ोन
   (arm64 + armeabi-v7a दोनों) में install हो जाती है। नाम हर build में same
   रहता है — link हमेशा latest APK देगा।
   छोटी per-ABI APK चाहिए (18 MB) तो Actions → run → Artifacts में मिलेगी।

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
`…-arm64-v8a-…` (≈18 MB), `…-armeabi-v7a-…` (≈18 MB), `…-universal-…` (≈30 MB,
यही release page पर `UltraSniffer.apk` नाम से publish होती है)।

### तरीका D — Tests चलाना (parsers + resolver की जाँच)

```bash
./gradlew testDebugUnitTest     # 15 JVM tests: HLS/DASH parsers, page extractor,
                                # YouTube pairing + link matching
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
5. Video download करने के 4 तरीके:
   * **YouTube**: YouTube app में video → **Share** → *Ultra Floating Video Sniffer*
     → quality list में से चुनें (1080p तक) → download + auto-merge.
     (या YouTube का link app के paste box में डालें।)
   * किसी भी app/browser में video चलाएँ → floating **"Download This Video"**
     bubble → tap → download शुरू। YouTube/FB जैसी sites पर bubble सीधे
     in-app browser खोलता है।
   * App में **🌐 In-app Browser** खोलें → site पर जाएँ → video चलाएँ →
     **🎬 इस page की video ढूँढो** (या जो **🎯 Video मिला** दिखे उसे) दबाएँ → quality चुनें।
   * किसी भी website का link paste/share करें → app page के अंदर से video
     खुद ढूँढकर options दिखाता है; कुछ न मिले तो **"फिर भी download करो"** भी है।
6. Download → merge → gallery (`Movies/UltraSniffer/`) तक का progress notification
   में दिखेगा; पूरा होने पर notification tap करके video चल सकते हैं।

### Settings (MainActivity में)

* **QUIC block करें** *(default ON)* — QUIC (UDP/443) block हो जाता है ताकि apps
  TCP पर आ जाएँ और sniffer को ज़्यादा दिखे (कुछ apps इससे slow हो सकते हैं)।
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
| YouTube download नहीं हुआ | YouTube का link app में **Share/Paste** करें — app खुद 1080p तक की qualities दिखाता है। (Sniffer सिर्फ HTTPS host hint देख सकता है, इसलिए normal YouTube app से direct पकड़ नहीं पाता — यह Android की limitation है, link वाला तरीका इसे पूरा हल करता है।) |
| "YouTube ने sign-in/bot-check लगाया" message | उस video को in-app browser में चलाएँ → **🎬 इस page की video ढूँढो** दबाएँ |
| Page से कोई video नहीं मिली | **"फिर भी download करो"** दबाएँ, या उसी page को in-app browser में खोलकर video चलाएँ (WebView अंदर से links पकड़ता है) |
| कुछ detect नहीं हो रहा | वह traffic HTTPS/QUIC है → **In-app Browser** use करें या link **Share/Paste** करें; "QUIC block" भी ON करें |
| Internet slow लगता है | Settings में QUIC block OFF करें (या sniffer बंद करें — बंद करते ही normal VPN-free internet) |
| Download में 403/404 | URL expire हो गया (signed CDN links कुछ देर में बदल जाते हैं) — video दोबारा चलाएँ और नया URL पकड़ें; YouTube links को तुरंत download करें |
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
| Tests | JUnit4 — 15 JVM tests: HLS/DASH parsers, page media extractor, YouTube pairing (`./gradlew testDebugUnitTest`, CI में भी चलते हैं) |
| Signing | demo keystore committed (`app/keystore/ultra-sniffer.keystore`) ताकि हर CI build installable + updatable हो |

---

## 8. कानूनी नोट (छोटा)

डाउनलोड सिर्फ़ उसी content का करें जिसे download करने का आपको अधिकार है।
DRM-protected streams (Netflix/Prime आदि) तकनीकी रूप से इस app से download
नहीं होते।
