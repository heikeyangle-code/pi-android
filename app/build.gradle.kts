import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.pi"
    // SDK 37 ships as a *minor-version* platform: there is no `platforms;android-37`
    // package, only 37.0/37.1/37.2. AGP 8.13.2 supports that scheme through
    // `compileSdkMinor`; `compileSdk = 37` alone resolves to the nonexistent
    // `platforms;android-37`. The minor setter must come *after* `compileSdk` —
    // it reads the API level already set on the extension.
    compileSdk = 37
    compileSdkMinor = 0

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

    // ---------------------------------------------------------------------
    // The runtime payload assets must be *stored*, not deflated — and their file
    // names must not end in `.gz`.
    //
    // ## Why the names matter (this was a total boot failure)
    //
    // The Android Gradle Plugin gunzips an asset whose file *extension* is `gz`
    // while it merges assets, and renames it at the same time:
    // `com.android.ide.common.resources.AssetItem` tests
    // `Files.getFileExtension(name).toLowerCase(Locale.US).equals("gz")` and then
    // applies `Files.getNameWithoutExtension`, which removes only the final `.gz`.
    // So the assembler wrote `assets/runtime/ubuntu-base.tar.gz` and the APK
    // contained `ubuntu-base.tar`: 106 MB of uncompressed tar instead of the
    // 28.5 MiB source file, under a name nothing asked for. `RuntimeProvisioner`
    // looks up `runtime/ubuntu-base.tar.gz`, `AssetManager.open()` answered with a
    // bare-path FileNotFoundException, and the runtime never provisioned — on
    // every build, from the first one:
    //
    //   packaged asset unreadable: runtime/ubuntu-base.tar.gz
    //   assets/runtime/ contains: fd.tar, node.tar, pi-engine.tar, ripgrep.tar, ubuntu-base.tar
    //
    // It is NOT aapt2: aapt2 2.20-14304508, run against a directory laid out like
    // this one, ships `ubuntu-base.tar.gz` and `pi-engine.tgz` under their own
    // names. The merge step runs first, so no flag on the aapt2 command line can
    // undo it — only the name can.
    //
    // The payloads are therefore named with `PAYLOAD_SUFFIX = ".tgz"` in
    // tools/fetch-runtime.mjs — the same gzip bytes, under an extension AGP leaves
    // alone (`Files.getFileExtension("ubuntu-base.tgz")` is `tgz`).
    // **Renaming any of them back to `.gz` re-breaks the app**, silently and with
    // a *smaller* APK as the only symptom.
    //
    // ## Why they must be stored
    //
    // A stored asset is served straight out of the APK's bytes, and it is also the
    // only kind `AssetManager.openFd()` can hand back a file descriptor for;
    // RuntimeProvisioner's reader has three layers (open/STREAMING, open/BUFFER,
    // openFd) and the third is only real because of this block.
    //
    // The list has to name the *actual* suffix: with `"gz"` listed and the files
    // named `.tgz`, none of the six payloads matched, every one of them was
    // deflated, and the `openFd()` layer went dead. `tgz` covers all six today.
    // `xz` and `tar` are kept so a future repack (the assembler already
    // re-compresses Node from `.tar.xz`) cannot quietly reintroduce a compressed
    // payload.
    //
    // AGP 8.13.2 DSL, checked against the artifacts and *compiled* against them
    // rather than recalled:
    //   * the block is `androidResources { }` — CommonExtension declares both
    //     `getAndroidResources()` and `androidResources(Function1)` in
    //     gradle-api-8.13.2.jar, and its receiver is ApplicationAndroidResources.
    //   * inside it the canonical spelling is the *property*:
    //         noCompress += listOf("tgz", "xz", "tar")
    //     `noCompress(String)` / `noCompress(String...)` still resolve but carry
    //     @Deprecated("Replaced with property noCompress"). Kotlin types the getter
    //     as the platform type (Mutable)Collection<String>!, so `+=` binds to
    //     MutableCollection.plusAssign — verified by compiling both spellings
    //     against the real jar with this project's Kotlin 2.2.21: the property
    //     form is clean, the method form emits the deprecation warning.
    //   * it is additive either way (AaptOptions calls Collections.addAll on the
    //     internal list), so AAPT2's own default no-compress extensions (.png,
    //     .jpg, …) are left in place.
    //   * AGP turns these entries into AAPT2's `--no-compress-regex`, a
    //     suffix-anchored, case-insensitive alternation — the `tgz` entry becomes
    //     the group `(t|T)(g|G)(z|Z)$` (PackagingUtils.getNoCompressForAapt ->
    //     AaptV2CommandBuilder.getNoCompressRegex). Matching on a suffix is why no
    //     leading dot is needed.
    //
    // This does NOT touch jniLibs. `packaging.jniLibs.useLegacyPackaging` is a
    // separate switch: it becomes
    // PackagingUtils.getNativeLibrariesLibrariesPackagingMode(Boolean) ->
    // NativeLibrariesPackagingMode, while `androidResources.noCompress` only
    // reaches AAPT2's *asset* compression. The four lib*.so files are packaged by
    // the `packaging {}` block below, unchanged.
    //
    // Storing the payloads makes the APK a little larger and the first launch a
    // little slower (nothing to inflate, but nothing compressed either). That is
    // the price of being able to open them at all.
    // ---------------------------------------------------------------------
    androidResources {
        noCompress += listOf("tgz", "xz", "tar")
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

    // The Workbench terminal's engine. Apache-2.0, Maven Central, and — the
    // reason it is a one-line dependency rather than a vendored source module —
    // the AAR ships its own prebuilt `libjni_cb_term.so`, so no NDK/CMake step
    // joins this build. It owns no process: `TerminalEmulatorFactory.create`
    // takes an `onKeyboardInput` callback and is fed through `writeInput`, so
    // `PtyLauncher` keeps the PTY. Version 0.0.13 is pinned deliberately; see
    // the ceiling note in gradle/libs.versions.toml before touching it.
    implementation(libs.termlib.android)

    // The elevated (uid=2000) shell backend. `api` is what ShizukuShellBackend
    // compiles against; `provider` is required at runtime for the binder handoff
    // (see the ShizukuProvider entry in AndroidManifest.xml). Both are MIT and
    // neither pulls native code.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
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
