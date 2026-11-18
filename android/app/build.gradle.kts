import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase config is per-deployment (see README "직접 빌드하기"). Without it the app builds but
// shows a "push not configured" state instead of pretending to work.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) apply(plugin = "com.google.gms.google-services")

val appVersion = providers.gradleProperty("app.version").orElse("0.1.0").get()
val appVersionCode = appVersion.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { (maj, min, pat) -> maj * 10000 + min * 100 + pat }

val relayUrl = providers.gradleProperty("app.relayUrl")
    .orElse("https://find-my-wife-phone-relay.jeiel85.workers.dev").get()

// --- Release signing (values live in release-signing.properties, never in git) ---------------
// One key for both channels (DECISIONS.md D-10): it signs the GitHub APK and is the Play upload
// key; Play's app signing key is the same key (D-9), so installs from either channel interchange.
val signingProps = Properties().apply {
    rootProject.file("release-signing.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signing(name: String): String? = providers.environmentVariable(name).orNull ?: signingProps.getProperty(name)
val storeFilePath = signing("FMWP_RELEASE_STORE_FILE")
val hasReleaseSigning = listOf(
    storeFilePath,
    signing("FMWP_RELEASE_STORE_PASSWORD"),
    signing("FMWP_RELEASE_KEY_ALIAS"),
    signing("FMWP_RELEASE_KEY_PASSWORD"),
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.jeiel85.findmywifephone"
    compileSdk = 36

    defaultConfig {
        // Kotlin namespace stays io.github.jeiel85.findmywifephone; the app id is what Android and
        // Play see (renamed for Play in v0.3.0 — DECISIONS.md D-9).
        applicationId = "com.jeiel85.ponerdi"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("boolean", "HAS_FIREBASE", "$hasFirebaseConfig")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(storeFilePath!!)
                storePassword = signing("FMWP_RELEASE_STORE_PASSWORD")
                keyAlias = signing("FMWP_RELEASE_KEY_ALIAS")
                keyPassword = signing("FMWP_RELEASE_KEY_PASSWORD")
            }
        }
    }

    // Separate builds per store (DECISIONS.md D-10): same applicationId and signing key,
    // but only the github build carries the self-updater and its install permissions.
    flavorDimensions += "channel"
    productFlavors {
        create("github") { dimension = "channel" }
        create("play") { dimension = "channel" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Play build only: asks Play whether a newer version exists (DECISIONS.md D-11).
    "playImplementation"("com.google.android.play:app-update-ktx:2.1.0")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
