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

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Debug signing so sideload builds are installable without a keystore.
            // A real release keystore is required before any store submission.
            signingConfig = signingConfigs.getByName("debug")
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
}
