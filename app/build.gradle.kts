plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermes.firetv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermes.firetv"
        minSdk = 25
        targetSdk = 26
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters("armeabi-v7a", "arm64-v8a")
        }
        buildConfigField("String", "DASHBOARD_URL", "\"http://2.24.198.162:8080\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isV1SigningEnabled = true
            isV2SigningEnabled = true
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
    implementation("androidx.core:core-ktx:1.3.2")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("com.google.android.material:material:1.3.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.4.0")
}
