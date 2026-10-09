plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.resilane.scene"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "VERSION_NAME", "\"0.4.0\"")
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("glb", "onnx") }
}
dependencies {
    api(project(if (rootProject.name == "RoadMesh") ":sim-core" else ":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    api("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.google.ar:core:1.48.0")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
