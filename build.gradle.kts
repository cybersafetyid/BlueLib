plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    id("bluelib.api-matrix")
}

/**
 * Fails when the version being published and the version compiled into the artifacts disagree.
 *
 * `BlueLibVersion.VERSION` is in source (BlueLib ships no generated code), so a release could otherwise
 * publish `1.2.0` while `BlueLib.version` still reports `1.0.0` — and every bug report would be
 * untraceable. The release workflow runs this before publishing anything.
 */
tasks.register("verifyVersionConsistency") {
    group = "verification"
    description = "Checks that BlueLibVersion.VERSION matches the bluelib.version Gradle property"

    val versionFile = layout.projectDirectory
        .file("bluelib-core/src/main/kotlin/io/github/cybersafetyid/bluelib/BlueLibVersion.kt")
    inputs.file(versionFile)
    inputs.property("bluelib.version", providers.gradleProperty("bluelib.version").orElse(""))

    doLast {
        val source = versionFile.asFile.readText()
        val declared = Regex("""VERSION:\s*String\s*=\s*"([^"]+)"""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: error("BlueLibVersion.VERSION was not found in ${versionFile.asFile.path}")

        val expected = providers.gradleProperty("bluelib.version").getOrElse("1.0.0-SNAPSHOT")
        check(declared == expected) {
            "Version mismatch: BlueLibVersion.VERSION is \"$declared\" but bluelib.version is \"$expected\". " +
                "Update BlueLibVersion.kt (and CHANGELOG.md) in the same commit as the version bump."
        }
        logger.lifecycle("BlueLib version $declared is consistent.")
    }
}
