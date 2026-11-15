# Credential Manager loads its Play Services provider by reflection.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# Google ID token credential is built from a Bundle by class name.
-keep class com.google.android.libraries.identity.googleid.** { *; }

# osmdroid reads tile sources and its config by reflection.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Keep line numbers so crash reports from release builds are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager's database is created by reflection (Room looks up WorkDatabase_Impl's no-arg constructor).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
# Workers are instantiated by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
