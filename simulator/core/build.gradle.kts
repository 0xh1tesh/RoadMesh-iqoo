plugins {
    id("org.jetbrains.kotlin.jvm")
}

// core is pure JVM: the shared-frame math, wire protocol, interpolation, node registry and
// YOLO evaluation. No Android dependency, so all of it is unit-tested on the laptop.

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Runs the exact ONNX model the phones ship, so the Kotlin pre/post-processing is checked
    // against Ultralytics on the laptop.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Built by the installed JDK 21 but targeting 17 bytecode, which the Android app (D8) consumes.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("resilane.root", rootProject.projectDir.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
