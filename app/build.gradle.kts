import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

android {
    namespace = "io.github.zapretdroid"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.zapretdroid"
        minSdk = 24
        targetSdk = 35
        // Test (debug) builds: the numbers below. Stable releases: CI passes -PappVersionName
        // and -PappVersionCode (their own numbering, 0.0.1 on).
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 60
        versionName = findProperty("appVersionName") as String? ?: "0.23.0"

        ndk {
            abiFilters += abis
        }
    }

    signingConfigs {
        // A fixed debug key in the repository: test builds from CI then update each other in
        // place (a new key per CI run meant uninstalling, and losing settings, every time).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release signing is taken from the environment so that CI can sign with a secret keystore.
        val keystore = System.getenv("ZAPRETDROID_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ZAPRETDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZAPRETDROID_KEY_ALIAS")
                keyPassword = System.getenv("ZAPRETDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore in the environment (CI secret), the repository key, so
            // that the APK installs; a stable build is a separate app (no ".debug" suffix).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // The test builds are what the releases ship: shrink them like a release, or every
            // library (Compose, Material, AppCompat, Gson…) goes in whole, ~14 MB of dex.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }

    // Lint never ran on the test builds; the stable (release) build should not be the first to stop on it.
    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The ByeByeDPI interface (classic/) uses views and ViewBinding.
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("hev/libs"))

    packaging {
        jniLibs.useLegacyPackaging = false
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

// hev-socks5-tunnel ships only an ndk-build script, while AGP drives a single external build
// (CMake for the ByeDPI engine), so the tunnel library is built by a separate ndk-build task.
val hevBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libhev-socks5-tunnel.so with ndk-build"

    val ndkDir = android.ndkDirectory
    val jniDir = file("src/main/jni")
    val outDir = layout.buildDirectory.dir("hev").get().asFile
    val ndkBuild = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "ndk-build.cmd"
    } else {
        "ndk-build"
    }

    inputs.dir(jniDir)
    outputs.dir(File(outDir, "libs"))

    executable = File(ndkDir, ndkBuild).absolutePath
    args(
        "NDK_PROJECT_PATH=${File(outDir, "obj").absolutePath}",
        "NDK_OUT=${File(outDir, "obj").absolutePath}",
        "NDK_LIBS_OUT=${File(outDir, "libs").absolutePath}",
        "APP_BUILD_SCRIPT=${File(jniDir, "Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${File(jniDir, "Application.mk").absolutePath}",
        "APP_ABI=${abis.joinToString(" ")}",
        "-j${Runtime.getRuntime().availableProcessors()}",
    )
}

tasks.named("preBuild") {
    dependsOn(hevBuild)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // ByeByeDPI interface (classic/), same libraries as ByeByeDPI 1.8.2.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for unit tests (android.jar has only stubs that throw).
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
