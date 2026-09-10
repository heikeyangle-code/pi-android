plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api`, not `implementation`: PiCommands returns JsonObject and PiEvent
    // carries JsonElement, so kotlinx-serialization is part of this module's
    // public surface. With `implementation` the :app module compiles against
    // types it cannot name.
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
