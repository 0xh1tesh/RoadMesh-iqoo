pluginManagement {
    resolutionStrategy.eachPlugin {
        when (requested.id.id) {
            "com.android.library", "com.android.application" -> useModule("com.android.tools.build:gradle:8.13.2")
            "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "resilane"

include(":core")
include(":app")
include(":scene")
