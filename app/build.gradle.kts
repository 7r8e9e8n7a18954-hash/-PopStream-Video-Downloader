plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ultra.sniffer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ultra.sniffer"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
    }

    signingConfigs {
        create("ultra") {
            // Demo signing key committed on purpose so every CI build is installable
            // and updatable with the same signature. Replace for production use.
            storeFile = file("keystore/ultra-sniffer.keystore")
            storePassword = "ultrasniffer"
            keyAlias = "ultra"
            keyPassword = "ultrasniffer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ultra")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("ultra")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        // Release APKs are produced automatically by CI on every push.
        // lintVital is a memory/time heavyweight on the build machine and is
        // not needed for the artifact itself — run `./gradlew lint` manually
        // when you want a full report.
        checkReleaseBuilds = false
        abortOnError = false
    }

    // One APK per ABI (+ one universal APK) so the FFmpeg native libraries
    // do not bloat every download. x86/x86_64 are only needed on emulators.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*"
        )
        jniLibs {
            // Phones only: keep x86 emulator libraries out of every APK.
            excludes += setOf("lib/x86/**", "lib/x86_64/**")
            // ffmpeg-kit ships its native .so files already stripped.
            // Skipping AGP's symbol-strip step keeps the build working in
            // plain SDK-only environments (no NDK required) and on CI.
            keepDebugSymbols += "**/*.so"
            // Compress the native libraries inside the APK. They are extracted
            // once at install time; the download shrinks by ~45%, which matters
            // a lot for the universal (all-ABI) APK.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("xmlpull:xmlpull:1.1.3.1")
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // ---------------------------------------------------------------------
    // Mobile-FFmpeg (ffmpeg-kit 6.0 LTS) — native ffmpeg used to
    //   * remux .ts / HLS chunks into a single high quality MP4
    //   * mux separate video + audio tracks (DASH / HLS audio renditions)
    // Served by Google's Maven-Central mirror (see settings.gradle.kts).
    // ---------------------------------------------------------------------
    implementation("com.arthenica:ffmpeg-kit-full:6.0-2")

    // The MediaMuxer based engine (download/MediaMerger.kt) stays in the app
    // as an automatic fallback for anything FFmpeg refuses to handle.
}
