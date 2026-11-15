# No blanket `-keep com.example.**`: that defeats shrinking. App code is referenced directly,
# so R8 can rename it. Only the reflective/JNI boundaries below need names preserved.

# Xray core: Go calls back into these bindings over JNI, and Room instantiates the generated
# database by class name, so both must survive shrinking and renaming.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn go.**
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keepclassmembers class * {
    @androidx.datastore.preferences.core.* <fields>;
}

# Coroutines & Serialization
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing QR Code
-keep class com.google.zxing.** { *; }
