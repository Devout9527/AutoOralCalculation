import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.lsplugin.resopt)
}

fun String.execute(): String {
    val byteOut = ByteArrayOutputStream()
    project.exec {
        commandLine = this@execute.split("\\s".toRegex())
        standardOutput = byteOut
    }
    return String(byteOut.toByteArray()).trim()
}

android {
    namespace = "cn.tinyhai.auto_oral_calculation"
    compileSdk = 34

    signingConfigs {
        val jks = file("../keystore.jks")
        if (jks.exists()) {
            register("release") {
                storeFile = jks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "cn.tinyhai.auto_oral_calculation"
        minSdk = 27
        targetSdk = 34
        versionCode = 74
        versionName = "2.0.32"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: getByName("debug").signingConfig
            versionNameSuffix = "-${"git rev-parse --verify --short HEAD".execute()}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    androidResources {
        additionalParameters += arrayOf("--allow-reserved-package-id", "--package-id", "0x23")
    }

    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    compileOnly(libs.api)
}