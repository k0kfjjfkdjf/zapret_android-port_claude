# Native methods are bound by name (byedpi JNI) and by RegisterNatives (hev-socks5-tunnel).
-keep class io.github.zapretdroid.core.ByeDpi { *; }
-keep class io.github.zapretdroid.core.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
