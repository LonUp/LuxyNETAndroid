# LuxyVPN Release R8 / ProGuard Hardening Rules

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Entrypoints & Android VPN Service
-keep public class com.example.MainActivity
-keep public class com.example.LuxyVpnApplication
-keep public class com.example.vpn.LuxyVpnService
-keep class com.example.vpn.** { *; }

# Room Database, Entities, DAOs & Generated _Impl classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.example.data.local.** { *; }
-dontwarn androidx.room.paging.**

# EncryptedConfigVault & Crypto / JCA / Keystore Helpers
-keep class com.example.core.EncryptedConfigVault { *; }
-keep class com.example.core.X25519KeyPair { *; }
-keep class com.example.core.VaultImportResult { *; }
-keep class com.example.core.VaultImportResult$* { *; }
-keep class com.example.core.** { *; }
-keepclassmembers class javax.crypto.** { *; }
-keepclassmembers class java.security.** { *; }

# LuxyVpnCore, Local SOCKS5/TUN Runtime, Adaptive Routing & Native/JNI Entrypoints
-keep class com.example.xraycore.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}

# Data Models, Enums & Serialization / JSON Reflection Preservation
-keep class com.example.data.model.** { *; }
-keepclassmembers enum com.example.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-dontwarn kotlinx.serialization.**

# DataStore & Coroutines
-keep class androidx.datastore.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**

# OkHttp / TLS / Conscrypt / ZXing
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn com.google.zxing.**
