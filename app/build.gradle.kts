plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lockguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lockguard"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }

    signingConfigs {
        create("shared") {
            storeFile = file("signing/lockguard.jks")
            storePassword = "lockguard"
            keyAlias = "lockguard"
            keyPassword = "lockguard"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}
