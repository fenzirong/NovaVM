# Default ProGuard rules for NovaVM.
-keep class com.novavm.app.engine.NativeEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.novavm.app.data.** {
    *** Companion;
}
