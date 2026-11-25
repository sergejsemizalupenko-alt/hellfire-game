plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.internetwake.app"
    compileSdk = 35

    defaultConfig {
        // Separate package so Lite 2.5.1 and TEST can remain installed.
        applicationId = "com.internetwake.app.lite.v252"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.5.2-lite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
