plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.internetwake.app"
    compileSdk = 35

    defaultConfig {
        // Separate package so Lite 2.5.1 and TEST can remain installed.
        applicationId = "com.internetwake.app.lite.v253"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.5.3-lite-side"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
