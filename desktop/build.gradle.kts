import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ByeZapret for Linux: the engine (ciadpi) as a local SOCKS5 proxy, with the app's strategy
// editor, presets and checks. Build and run: ./gradlew -PnoAndroid :desktop:run
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
}

// The app's resources: the engine built for this machine and the Android app's assets (fakes,
// host lists, presets, Telegram ranges). Compose puts them next to the app (also for :run) and
// tells the app where through the "compose.application.resources.dir" property.
val engineSources = rootProject.file("app/src/main/cpp/engine")
val appResources = layout.buildDirectory.dir("app-resources")

val buildEngine by tasks.registering(Exec::class) {
    val out = appResources.map { it.file("linux/ciadpi") }
    inputs.dir(engineSources)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine(
        "sh", "-c",
        "cc -O2 -std=gnu99 -D_DEFAULT_SOURCE -I\"\$1\" \"\$1\"/*.c -o \"\$2\" -lpthread",
        "sh", engineSources.absolutePath, out.get().asFile.absolutePath,
    )
}

val copyAssets by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets")) {
        include("fake/**", "lists/**", "strategies/**", "telegram-ip.txt")
    }
    into(appResources.map { it.dir("common/assets") })
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(buildEngine, copyAssets)
}

compose.desktop {
    application {
        mainClass = "io.github.zapretdroid.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "byezapret"
            packageVersion = "0.0.1"
            description = "DPI bypass with ByeDPI (local SOCKS5 proxy)"
            appResourcesRootDir.set(appResources)
        }
    }
}
