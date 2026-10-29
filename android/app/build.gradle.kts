plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahm.voicechat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ahm.voicechat"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.11"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WebSocket client for the relay protocol. No other network library is used.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
