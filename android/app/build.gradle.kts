import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release signing key lives outside the repo, by default in ~/.android/video-downloader/
// (see README.md). Without it, release APKs come out unsigned.
val signing = Properties().apply {
    val path = System.getenv("VD_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.android/video-downloader/keystore.properties"
    File(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.sivasubburaj.videodownloader"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "io.github.sivasubburaj.videodownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (!signing.isEmpty) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // yt-dlp's Python and FFmpeg are about 50 MB per CPU type, so build one APK per type
    // instead of a 200 MB universal one. x86_64 is for the emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
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
        compose = true
        buildConfig = true
    }
    packaging {
        // youtubedl-android runs Python and FFmpeg straight from the native library folder,
        // so the libraries must be extracted on install (extractNativeLibs="true")
        jniLibs.useLegacyPackaging = true
    }
}

// VideoDownloader-<version>-android-<abi>.apk instead of app-<abi>-release.apk
android.applicationVariants.all {
    val variant = this
    outputs.all {
        val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val abi = output.getFilter("ABI") ?: "universal"
        val suffix = if (variant.buildType.name == "release") "" else "-${variant.buildType.name}"
        output.outputFileName = "VideoDownloader-${variant.versionName}-android-$abi$suffix.apk"
    }
}

dependencies {
    val ytdlp = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlp")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlp")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
