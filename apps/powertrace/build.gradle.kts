plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mobilelab.powertrace"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.mobilelab.powertrace"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "1.4.0"

        // Research instrument targets 64-bit ARMv8-A devices only.
        // No 32-bit (armeabi-v7a) or x86 native code is shipped.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // 课程要求：仅 arm64-v8a（ARMv8 基线），因此 ChromeOS x86_64 ABI 提示不适用。
        disable += "ChromeOsAbiSupport"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))


    testImplementation(libs.junit)
}
