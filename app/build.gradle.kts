plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vhscamera.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vhscamera.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Sideload key kept in the repo on purpose so every CI build has the same
        // signature and installs over the previous one.
        create("release") {
            storeFile = file("vhscam.jks")
            storePassword = "vhscam2025"
            keyAlias = "vhscam"
            keyPassword = "vhscam2025"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
