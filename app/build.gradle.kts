plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.internetwake.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.internetwake.app.v23"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
