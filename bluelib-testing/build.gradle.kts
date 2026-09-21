plugins {
    id("bluelib.kotlin.jvm")
    id("bluelib.publish")
}

dependencies {
    api(project(":bluelib-core"))
    testImplementation(libs.kotlinx.coroutines.test)
}
