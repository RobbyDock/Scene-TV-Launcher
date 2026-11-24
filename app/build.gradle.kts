import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signingProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.robbydock.scene"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.robbydock.scene"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"
    }

    // Signs release builds when local.properties has the release signing keys.
    val storePath = signingProps.getProperty("release.storeFile")
    val releaseSigning = if (!storePath.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(storePath)
            storePassword = signingProps.getProperty("release.storePassword")
            keyAlias = signingProps.getProperty("release.keyAlias")
            keyPassword = signingProps.getProperty("release.keyPassword")
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning != null) signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }
}

// No runtime dependencies.

// JVM unit tests.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
