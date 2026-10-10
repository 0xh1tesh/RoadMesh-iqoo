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
    // QNN build of ONNX Runtime: the same CPU runtime plus the Hexagon NPU provider. QNN 2.50 is the runtime the NPU
    // probe verified on the iQOO 15 (SM8850); ORT's own pin is older.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")
}
