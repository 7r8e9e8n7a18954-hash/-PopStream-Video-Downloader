# Keep OkHttp internals (it uses reflection for platform detection)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Reflection-based optional ffmpeg-kit hook
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# WebView JavaScript bridge (methods are called from JS by name)
-keepclassmembers class com.ultra.sniffer.ui.BrowserActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep MediaStore / service classes referenced from the manifest
-keep class com.ultra.sniffer.vpn.** { *; }
-keep class com.ultra.sniffer.overlay.** { *; }
-keep class com.ultra.sniffer.download.** { *; }
