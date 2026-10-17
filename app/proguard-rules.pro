# Native methods are bound by name (byedpi JNI) and by RegisterNatives (hev-socks5-tunnel).
-keep class io.github.zapretdroid.core.ByeDpi { *; }
-keep class io.github.zapretdroid.core.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ByeByeDPI interface (classic/): preference screens create fragments by name, Gson stores data classes.
-keep class io.github.zapretdroid.classic.fragments.** { <init>(); }
-keep,allowoptimization class io.github.zapretdroid.classic.data.** { <fields>; }
-keepattributes Signature
-keepattributes *Annotation*
