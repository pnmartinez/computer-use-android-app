# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ============================================
# ATRIBUTOS PARA DEBUGGING
# ============================================
# Preservar información de línea para stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preservar anotaciones
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============================================
# SERVICIOS Y ACTIVITIES
# ============================================
# Mantener todas las clases de servicios y actividades
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Mantener constructores de servicios
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.content.Intent);
}

# ============================================
# OKHTTP / WEBSOCKET
# ============================================
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# WebSocket
-keep class okhttp3.internal.ws.** { *; }

# ============================================
# KOTLIN COROUTINES
# ============================================
# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================
# MEDIA3 / EXOPLAYER
# ============================================
# Media3
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ============================================
# ANDROIDX (general)
# ============================================
# Mantener clases de AndroidX que se usan por reflexión
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ============================================
# REFLECTION / SERIALIZATION
# ============================================
# Si usas JSON serialization (Gson, Moshi, etc.)
# -keep class com.google.gson.** { *; }
# -keep class * implements com.google.gson.TypeAdapterFactory
# -keep class * implements com.google.gson.JsonSerializer
# -keep class * implements com.google.gson.JsonDeserializer

# ============================================
# CLASES DE LA APLICACIÓN
# ============================================
# Mantener clases principales
-keep class com.example.myapplication.** { *; }

# Mantener métodos nativos
-keepclasseswithmembernames class * {
    native <methods>;
}

# Mantener métodos de callback de View
-keepclassmembers class * extends android.view.View {
    void set*(***);
    *** get*();
}

# Mantener Parcelables
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}