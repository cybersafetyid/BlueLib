plugins {
    id("bluelib.android.library")
    id("bluelib.publish")
}

android {
    namespace = "io.github.cybersafetyid.bluelib.facade"

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":bluelib-core"))
    api(project(":bluelib-android"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bluelib-testing"))
}
