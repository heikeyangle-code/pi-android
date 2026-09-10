pluginManagement {
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

rootProject.name = "pi-android"

// Pure Kotlin/JVM protocol core. Deliberately an Android-free module so the
// RPC framing, command builders, event parsing and transcript projection can be
// unit-tested with nothing but a JDK (see docs/pi-android-app-design.md §7.1).
include(":rpc")

// The Android application: Compose UI, runtime provisioning, device bridge.
include(":app")
