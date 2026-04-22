import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read injected values from gradle.properties (CI injects these at build time).
// Fallback to 0 for local debug builds.
val ciBuildNumber: String = run {
    val props = Properties()
    project.rootProject.file("gradle.properties").inputStream().use { props.load(it) }
    props.getProperty("CI_BUILD_NUMBER", "0")
}
val dashboardAuthToken: String = run {
    val props = Properties()
    project.rootProject.file("gradle.properties").inputStream().use { props.load(it) }
    props.getProperty("dashboardAuthToken", "")
}

android {
    namespace = "com.hermes.firetv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermes.firetv"
        minSdk = 22
        targetSdk = 34
        // CI_BUILD_NUMBER is injected by the workflow: 1, 2, 3… each run.
        // Falls back to 1 in local builds so debug APKs have a valid versionCode.
        versionCode = (ciBuildNumber.toIntOrNull() ?: 1).coerceAtLeast(1)
        versionName = "1.0.${ciBuildNumber.toIntOrNull() ?: 0}"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        // Auth token injected by CI from secrets.DASHBOARD_AUTH_TOKEN.
        // Source tree always has an empty string here.
        buildConfigField("String", "DASHBOARD_URL", "\"https://dashboard.cashlabnyc.com\"")
        buildConfigField("String", "AUTH_TOKEN", "\"$dashboardAuthToken\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("Boolean", "ENABLE_WEBVIEW_DEBUG", "true")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            buildConfigField("Boolean", "ENABLE_WEBVIEW_DEBUG", "false")
            // CI pipeline (build.yml) re-signs this APK with UPLOAD_KEYSTORE_B64
            // after the assemble step. Local `gradle assembleRelease` uses
            // signingConfigs.release (debug keystore, for dev only).
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Webkit 1.10.0: security fix, Fire OS 7 compatibility, better long-session stability
    implementation("androidx.webkit:webkit:1.10.0")

    // NOTE: ACRA crash reporting removed — ch.acra:acra-telegram does not exist
    // in Maven Central. If crash reporting is needed in future, use Firebase Crashlytics
    // or a custom file-based logger instead.
}
