plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.ai_image_generator_application"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ai_image_generator_application"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"AIzaSyBPAxWTGQcOlPT3Qi2gTQSRZCMnGlEVsWg\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.glide)
    implementation(libs.volley)
    implementation(libs.picasso)
    implementation(libs.generativeai)
    implementation(libs.guava)

    // CameraX core library using the camera2 implementation
    val camerax_version = "1.5.0-alpha06" // Kiểm tra phiên bản mới nhất
    implementation ("androidx.camera:camera-core:${camerax_version}")
    implementation ("androidx.camera:camera-camera2:${camerax_version}")
    // CameraX Lifecycle Library
    implementation ("androidx.camera:camera-lifecycle:${camerax_version}")
    // CameraX View class
    implementation ("androidx.camera:camera-view:${camerax_version}")

    // ML Kit Text Recognition
    implementation ("com.google.mlkit:text-recognition:16.0.1") // Kiểm tra phiên bản mới nhất
}
