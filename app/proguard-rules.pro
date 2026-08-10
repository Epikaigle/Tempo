# Tempo R8 rules.
#
# Kept intentionally minimal: Retrofit, OkHttp, Moshi (codegen), Coil, Coroutines,
# Compose, Hilt, Room, WorkManager, Glance and Play Services all ship consumer
# ProGuard rules in their AARs — re-keeping them here only blocks shrinking,
# obfuscation and optimization. Manifest-declared components (activities,
# services, receivers, widget receivers) are auto-kept by AGP.
# Only what reflection reaches *by name* is listed below.

# ── Reflective Moshi (KotlinJsonAdapterFactory) ──
# Backup export/import serializes Room entities reflectively (TempoExportData has
# codegen, its nested entities don't), and session persistence serializes
# SerializableSession. Field + constructor names are the JSON keys — they must
# survive. Class names may still be obfuscated (lookups use Class objects and
# Kotlin metadata, which R8 rewrites consistently).
-keepclassmembernames class me.avinas.tempo.data.local.entities.** { <fields>; <init>(...); }
-keepclassmembernames class me.avinas.tempo.service.SerializableSession { <fields>; <init>(...); }

# Custom Moshi adapters are found by @FromJson/@ToJson annotation scan
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# Kotlin metadata is read by Moshi's reflective/kotlin support
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ── Enums ──
# Stored by name in SQLite (TypeConverters) and JSON. Renaming constants would
# corrupt existing user data on upgrade.
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# ── WorkManager ──
# The worker class NAME is stored in WorkManager's DB (survives app updates) and
# HiltWorkerFactory looks the worker up by that name. Names must stay stable;
# bodies are still optimized.
-keepnames class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Retrofit ──
# Service interface methods are invoked through annotations at runtime.
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}

# ── Google Drive API ──
# GenericJson models are populated via @Key field reflection; no-arg construction.
# (Drive model classes only — the api-client internals are referenced directly.)
-keepclassmembers class * extends com.google.api.client.json.GenericJson { *; }
# Keep the Drive API + Google auth client class names. R8 may otherwise rename
# these (they are loaded/reflected through Google Play services binders), which
# breaks Drive API calls with cryptic server errors.
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }
-keep class com.google.android.gms.common.api.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**

# ── Parcelable ──
# CREATOR is read reflectively by the framework.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ── Attributes ──
# Annotations/generics needed by Retrofit, Moshi and kotlin-reflect;
# line numbers kept so crash stack traces stay readable.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Obfuscation ──
-repackageclasses ''
-allowaccessmodification

# ── Strip debug logging from release ──
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
