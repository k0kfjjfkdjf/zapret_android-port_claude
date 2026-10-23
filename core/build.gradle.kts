import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The part of ByeZapret without Android: strategy scripts, command-line references, DoH/DNSCrypt,
// site and UDP probes, the engine's JNI binding. Used by the Android app and the desktop app.
plugins {
    id("org.jetbrains.kotlin.jvm")
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
    // Android has org.json built in; the desktop app adds the library itself.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
