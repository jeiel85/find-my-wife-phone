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

// --- Release signing (values live in *-signing.properties, never in git) ---------------------
// Each channel signs with its own key (DECISIONS.md D-10): github = the key sideloaded phones
// verify updates against; play = the Play upload key (Google re-signs with the app signing key).
fun signingProps(file: String) = Properties().apply {
    rootProject.file(file).takeIf { it.exists() }?.inputStream()?.use(::load)
}
val githubSigning = signingProps("release-signing.properties")
val playSigning = signingProps("play-upload-signing.properties")
fun signing(props: Properties, name: String): String? = providers.environmentVariable(name).orNull ?: props.getProperty(name)

/** Returns store file, store password, alias, key password when all are set. */
fun signingValues(props: Properties, prefix: String): List<String>? =
    listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .map { signing(props, "${prefix}_$it") }
        .takeIf { values -> values.all { !it.isNullOrBlank() } }
        ?.map { it!! }
val githubKey = signingValues(githubSigning, "FMWP_RELEASE")
val playKey = signingValues(playSigning, "FMWP_PLAY_UPLOAD")

android {
    namespace = "io.github.jeiel85.findmywifephone"
    compileSdk = 35

    defaultConfig {
        // Kotlin namespace stays io.github.jeiel85.findmywifephone; the app id is what Android and
        // Play see (renamed for Play in v0.3.0 — DECISIONS.md D-9).
        applicationId = "com.jeiel85.ponerdi"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("boolean", "HAS_FIREBASE", "$hasFirebaseConfig")
    }

    signingConfigs {
        githubKey?.let { (file, storePass, alias, keyPass) ->
            create("github") {
                storeFile = rootProject.file(file); storePassword = storePass
                keyAlias = alias; keyPassword = keyPass
            }
        }
        playKey?.let { (file, storePass, alias, keyPass) ->
            create("playUpload") {
                storeFile = rootProject.file(file); storePassword = storePass
                keyAlias = alias; keyPassword = keyPass
            }
        }
    }

    // Separate builds per store (DECISIONS.md D-10). Same applicationId, different signing keys
    // on the upload side; only the github build updates itself.
    flavorDimensions += "channel"
    productFlavors {
        create("github") {
            dimension = "channel"
            if (githubKey != null) signingConfig = signingConfigs.getByName("github")
        }
        create("play") {
            dimension = "channel"
            if (playKey != null) signingConfig = signingConfigs.getByName("playUpload")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
