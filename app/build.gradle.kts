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
        versionCode = 12
        versionName = "0.8.2"

        ndk {
            abiFilters += abis
        }
    }

    signingConfigs {
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
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
