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
