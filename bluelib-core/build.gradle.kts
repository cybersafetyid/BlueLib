plugins {
    id("bluelib.kotlin.jvm")
    id("bluelib.publish")
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    // `runTest` gives the domain tests a virtual clock, so retry and timeout policies are verified
    // without waiting for real time to pass.
    testImplementation(libs.kotlinx.coroutines.test)
}

/** Sanity check used by CI to make sure the domain layer never links against the Android framework. */
val verifyNoAndroidImports by tasks.registering {
    group = "verification"
    description = "Fails if the domain layer imports android.* or androidx.*"
    val sources = layout.projectDirectory.dir("src")
    inputs.dir(sources)
    doLast {
        val offenders = sources.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val trimmed = line.trim()
                    (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) &&
                        !trimmed.startsWith("//")
                }
            }
            .map { it.relativeTo(projectDir).path }
            .toList()
        check(offenders.isEmpty()) {
            "The domain layer must stay free of Android dependencies, but found: $offenders"
        }
    }
}

tasks.named("check") { dependsOn(verifyNoAndroidImports) }
