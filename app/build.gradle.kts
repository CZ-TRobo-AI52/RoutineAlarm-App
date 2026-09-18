plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cztr.routinealarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cztr.routinealarm"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1-signed"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ROUTINEALARM_KEYSTORE_PATH")

            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ROUTINEALARM_STORE_PASSWORD")
                keyAlias = System.getenv("ROUTINEALARM_KEY_ALIAS")
                keyPassword = System.getenv("ROUTINEALARM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
