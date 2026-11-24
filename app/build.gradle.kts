plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.internetwake.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.internetwake.app.v24"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.4.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
