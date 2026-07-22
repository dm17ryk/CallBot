plugins {
    id("com.android.application")
}

android {
    namespace = "com.essence.callbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.essence.callbot"
        minSdk = 29         // Android 10+
        targetSdk = 34      // Android 14 (POCO X5 Pro 5G / HyperOS 2.0)
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
