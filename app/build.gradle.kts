import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.launchapk.java"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.launchapk.java"
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":input"))
    implementation(project(":core"))
    implementation(project(":auth"))
    implementation(project(":launcher"))
    implementation(project(":nativebridge"))
    implementation(project(":renderer"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
