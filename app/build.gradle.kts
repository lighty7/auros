import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val lp = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

/** A value from the environment (CI secrets) first, then local.properties. */
fun setting(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: lp.getProperty(prop)?.takeIf { it.isNotBlank() }

// Release versions come from the git tag in CI (-PversionName=1.2.3 -PversionCode=10203); local builds use the defaults.
val appVersionName = (findProperty("versionName") as String?) ?: "0.2"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 2

// Release signing: from CI secrets or local.properties. Without them a release build falls back to the debug key (fine locally, never published).
val keystoreFile = setting("AURA_KEYSTORE_FILE", "release.storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.aura.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.aura.app"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "ORS_KEY", "\"${setting("ORS_KEY", "ors.key") ?: ""}\"")
    }
    signingConfigs {
        if (keystoreFile != null) create("release") {
            storeFile = keystoreFile
            storePassword = setting("AURA_KEYSTORE_PASSWORD", "release.storePassword")
            keyAlias = setting("AURA_KEY_ALIAS", "release.keyAlias")
            keyPassword = setting("AURA_KEY_PASSWORD", "release.keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
