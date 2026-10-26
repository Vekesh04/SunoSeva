# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ==================== SunoSeva ProGuard Rules ====================

# Keep application classes
-keep class com.example.sunoseva.** { *; }

# ==================== Retrofit & OkHttp ====================
-keepattributes Signature
-keepattributes Exceptions

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ==================== Gson ====================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep Gson serialized classes
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ==================== GeminiService Data Classes ====================
# These must be kept for JSON serialization/deserialization
-keep class com.example.sunoseva.GeminiService$* { *; }

# ==================== API Key Protection ====================
# Obfuscate BuildConfig but keep API key accessible
-keep class com.example.sunoseva.BuildConfig { *; }

# ==================== Speech Recognition ====================
-keep class android.speech.** { *; }

# ==================== Debug ====================
# Uncomment for debugging
#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile

# ==================== Remove Logging in Release ====================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}