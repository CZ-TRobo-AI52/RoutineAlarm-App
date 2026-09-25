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
        versionCode = 3
        versionName = "0.2.0-alpha01"
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
