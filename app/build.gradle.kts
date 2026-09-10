import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.pi"
    compileSdk = 36

    // ---------------------------------------------------------------------
    // targetSdk is a *runtime capability switch*, not a style choice.
    //
    // Android 10 (API 29) stopped letting an app execve() files in its own data
    // directory. pi's whole runtime lives there (the Ubuntu rootfs, Node, every
    // tool), so the choice is:
    //
    //   28  the pre-W^X sandbox. Termux pins 28 for exactly this reason, and the
    //       local DSH app on this device ships proot via jniLibs + PROOT_LOADER
    //       which is only necessary at >= 29. Known good. Play-incompatible.
    //   36  Play-viable: proot's loader is exec'd from nativeLibraryDir and maps
    //       the guest binaries itself. Reported working on API 35/36, but not
    //       proven on this device yet.
    //
    // Default is 28 so a sideloaded build is known-good; CI also produces the
    // 36 variant (`-Ppi.targetSdk=36`) so the modern path can be validated on
    // hardware instead of guessed at.
    // ---------------------------------------------------------------------
    val piTargetSdk = (project.findProperty("pi.targetSdk") as String?)?.toInt() ?: 28

    defaultConfig {
        applicationId = "app.pi"
        minSdk = 26
        targetSdk = piTargetSdk
        versionCode = 1
        versionName = "0.1.0"

        // The bundled runtime is aarch64-only: proot loader, glibc rootfs, Node.
        // One ABI, no `splits`: the APK carries hundreds of prebuilt binaries and
        // shipping a second ABI would double the payload for no user.
        ndk { abiFilters += "arm64-v8a" }
    }

    /**
     * A committed sideload key, on purpose.
     *
     * CI's generated debug keystore differs on every run, so every build would
     * carry a different signature and refuse to install over the previous one —
     * the user would have to uninstall first, every single time. With a fixed key
     * the app upgrades in place.
     *
     * The trade-off is real and deliberate: the key is in the repo, so anyone who
     * can write to the repo can sign an update. That is acceptable for a personal
     * sideload build and **not** acceptable for anything distributed through a
     * store — those must set `PI_KEYSTORE` properties instead (see below).
     */
    signingConfigs {
        create("sideload") {
            // An out-of-band distribution key wins if supplied; otherwise the
            // committed sideload key.
            val external = System.getenv("PI_KEYSTORE")
            storeFile = if (external != null) file(external) else rootProject.file("keystore/pi-sideload.jks")
            storePassword = System.getenv("PI_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("PI_KEY_ALIAS") ?: "pi"
            keyPassword = System.getenv("PI_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val hasKey = System.getenv("PI_KEYSTORE") != null ||
                rootProject.file("keystore/pi-sideload.jks").exists()
            signingConfig =
                if (hasKey) signingConfigs.getByName("sideload") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Executables disguised as lib*.so must be written to disk, not mmapped
        // from the APK: Android 10+ refuses execve() on app_data_file, and
        // nativeLibraryDir is the one writable-by-us location that is executable.
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module",
        )
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":rpc"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.documentfile)
    implementation(libs.webkit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.markdown.renderer.m3)
}

// The markdown renderer is built by a newer Kotlin than this project's compiler
// and therefore asks for kotlin-stdlib 2.4.0 (and coroutines 1.11.0). Kotlin
// 2.2.21 refuses to read 2.4.0 metadata:
//
//   kotlin-stdlib-2.4.0.jar!/META-INF/kotlin-stdlib.kotlin_module:
//     error: module was compiled with an incompatible version of Kotlin.
//     The binary version of its metadata is 2.4.0, expected version is 2.2.0.
//
// — verified by compiling against that jar on this machine. Pinning the
// standard library back to the compiler's own version is safe because the
// renderer only refers to long-standing stdlib and coroutines members
// (Pair/TuplesKt/CollectionsKt/EnumEntries, and Flow/StateFlow/Mutex/
// CoroutineScope); a scan of every referenced `kotlin/` and `kotlinx/coroutines`
// class in its 411 class files found nothing newer than Kotlin 1.9.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${libs.versions.coroutines.get()}")
    }
}
