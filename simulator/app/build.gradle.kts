plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.resilane.sim"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.resilane.sim"
        // ARCore requires API 24+.
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            // Same signing on every build so `adb install -r` keeps working across phones.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { buildConfig = true }

    sourceSets["main"].java.srcDir("src/main/kotlin")

    androidResources {
        // GLB and ONNX are already compressed / must be memory-mappable.
        noCompress += listOf("glb", "onnx")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // 6-DoF tracking, plane detection, Augmented Images (marker calibration), HDR light estimation.
    implementation("com.google.ar:core:1.48.0")

    // Peer-to-peer pose exchange with no server.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // On-device YOLO over the composited AR frame.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
