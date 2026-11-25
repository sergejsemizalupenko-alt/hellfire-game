plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.internetwake.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.internetwake.app.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.5.2-lite"
    }

    signingConfigs {
        create("stable") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("IW_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("IW_KEY_ALIAS")
            keyPassword = System.getenv("IW_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
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
