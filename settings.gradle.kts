pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ------------------------------------------------------------------
        // Mobile-FFmpeg (ffmpeg-kit) is no longer published on Maven Central,
        // but the prebuilt AARs are still served by Google's official
        // Maven-Central mirror. This is what gives us real FFmpeg for
        // ts -> mp4 remuxing and video+audio muxing.
        // ------------------------------------------------------------------
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content {
                includeGroup("com.arthenica")
            }
        }
    }
}

rootProject.name = "UltraFloatingVideoSniffer"
include(":app")
