plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jsons.roadmesh"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jsons.roadmesh"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        // Loaners are arm64; override for future targets or an emulator without changing source.
        ndk { abiFilters += providers.gradleProperty("roadmeshAbis").orElse("arm64-v8a").get().split(',').map { it.trim() } }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // The ONNX detector and the dashcam clip are read in place from the APK.
    androidResources { noCompress += listOf("onnx", "mp4") }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":scene"))
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // On-device LLM (Gemma 2B int4 on CPU) for the co-pilot. The model file is imported at run time, never bundled.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    testImplementation("junit:junit:4.13.2")
    // Screen renders on the JVM (Robolectric native graphics + Roborazzi). Only compiled with -PwithScreenshots=true.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20240303")
}

if (providers.gradleProperty("withScreenshots").orNull != "true") {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name.contains("UnitTest")) exclude("**/ScreenshotTest.kt")
    }
} else {
    // Robolectric runs offline from a runtime jar placed in build/robolectric-jars (see docs/prototype-app.md), and
    // writes its temporary files under build/, so a nearly full system drive does not break the run.
    tasks.withType<Test>().configureEach {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
        systemProperty("java.io.tmpdir", layout.buildDirectory.dir("tmp-tests").get().asFile.absolutePath)
        maxHeapSize = "3g"
    }
}
