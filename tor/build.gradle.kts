import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Tor module: a separate APK that ByeZapret downloads and installs only for those who want
// Tor without a second app (Orbot, InviZible). Tor from the Guardian Project (tor-android),
// bridge transports from IPtProxy (Lyrebird: obfs4, meek_lite, webtunnel; Snowflake). Same
// signing key as the app: the app checks the signature before using it, and the control
// permission is signature-level.
val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val torVersion = "0.4.9.14"

android {
    namespace = "io.github.zapretdroid.tor"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.zapretdroid.tor"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-tor$torVersion"
        // The permission the app holds to control the module; defined by both, signature-level.
        manifestPlaceholders["controlPermission"] = "io.github.zapretdroid.permission.CONTROL_TOR"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["controlPermission"] = "io.github.zapretdroid.debug.permission.CONTROL_TOR"
        }
        release {
            isMinifyEnabled = false
        }
    }

    // One APK per ABI: Tor and the transports are ~30 MB of native code per ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        // Compressed in the APK (a smaller download), extracted at install.
        jniLibs.useLegacyPackaging = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("info.guardianproject:tor-android:$torVersion")
    implementation("com.netzarchitekten:IPtProxy:5.7.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}
