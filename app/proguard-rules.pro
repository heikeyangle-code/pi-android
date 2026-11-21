# Pi for Android R8 / ProGuard rules
# Keep crash stack traces readable (for PiCrashStore & DiagnosticsReport)
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Android entry points (also kept by AAPT2, reinforced explicitly)
-keep class app.pi.PiApplication { *; }
-keep class app.pi.MainActivity { *; }
-keep class app.pi.service.PiEngineService { *; }
-keep class app.pi.bridge.DeviceAccessibilityService { *; }
-keep class app.pi.bridge.PiNotificationListener { *; }

# ConnectBot termlib JNI callbacks (called from libjni_cb_term.so)
-keep class org.connectbot.termlib.** { *; }
-dontwarn org.connectbot.termlib.**

# Shizuku Binder / AIDL / ContentProvider
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# AndroidSVG (CSS/SAX reflection & parser)
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# SnakeYAML & Jsoup
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
-dontwarn org.jsoup.**

# kotlinx.serialization / coroutines runtime safety
-dontwarn kotlinx.serialization.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.markdown.**
-dontwarn com.mikepenz.markdown.**
