plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    // Applied by the convention plugins, so the jar has to be on the build-logic classpath.
    implementation(libs.dokka.gradle.plugin)
}
