plugins {
    id("bluelib.android.library")
    id("bluelib.publish")
}

android {
    namespace = "io.github.cybersafetyid.bluelib.android"

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":bluelib-core"))

    // The Android artifact of the coroutines library brings the main dispatcher and the Android
    // specific exception handling that a Bluetooth callback thread needs.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric is only used where a test genuinely needs framework behaviour; the domain tests run
    // on a plain JVM (see docs/guides/testing.md).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
