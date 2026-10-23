// The plugins of all modules (Compose Multiplatform for the desktop app), in one classloader (the Kotlin plugin must see AGP). The Android
// plugin only when the app is built: -PnoAndroid (the core and the desktop app on a machine
// without the Android SDK) does not need Google's repository.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (!gradle.startParameter.projectProperties.containsKey("noAndroid")) {
            classpath("com.android.tools.build:gradle:8.11.0")
        }
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.0")
        classpath("org.jetbrains.compose:compose-gradle-plugin:1.8.2")
    }
}
