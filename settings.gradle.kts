pluginManagement {
    resolutionStrategy.eachPlugin {
        when (requested.id.id) {
            "com.android.library" -> useModule("com.android.tools.build:gradle:8.13.2")
            "org.jetbrains.kotlin.jvm" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RoadMesh"
include(":app")
include(":core", ":sim-core", ":scene", ":simulator-app")
project(":sim-core").projectDir = file("simulator/core")
project(":scene").projectDir = file("simulator/scene")
project(":simulator-app").projectDir = file("simulator/app")
