import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional fixed signing key so each new APK installs over the last one.
// The GitHub workflow sets these from repo secrets; without them the debug key is used.
val keystorePath: String? = System.getenv("PORTAL_KEYSTORE")

android {
    namespace = "tech.atelier.portal"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.atelier.portal"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PORTAL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PORTAL_KEY_ALIAS")
                keyPassword = System.getenv("PORTAL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

base {
    archivesName.set("PORTAL-" + android.defaultConfig.versionName)
}

// No dependencies on purpose: plain Android framework + Kotlin stdlib, like OrbitSearch.
