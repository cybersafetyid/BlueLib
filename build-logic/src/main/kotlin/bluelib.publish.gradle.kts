import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("maven-publish")
    id("signing")
}

/**
 * Everything needed to publish a BlueLib artifact.
 *
 * Design decisions worth knowing:
 *
 * * **Artifacts are staged locally, not uploaded implicitly.** `publishAllPublicationsToStagingRepository`
 *   fills `<root>/build/staging-deploy` — one directory shared by every module, laid out the way the
 *   Central Portal expects a bundle — which the release workflow zips and uploads. A build that cannot
 *   reach the network cannot half-publish a release.
 * * **Signing is opt-in.** It is only configured when a non-blank key is present, so `./gradlew build`
 *   works for contributors who have no PGP key. A *blank* key counts as absent: GitHub expands a secret
 *   that was never created to an empty string, and a release that fails with "no signature" is far
 *   better than one that fails inside the signing plugin. The release workflow checks the secrets up
 *   front for the same reason.
 * * **The password is never null.** Gradle 9.5's `useInMemoryPgpKeys(key, null)` installs a signatory
 *   provider whose default signatory is *null* — no exception, no warning. The build then dies at the
 *   signing task with "Cannot perform signing task ':x' because it has no configured signatory", which
 *   points at the task rather than the missing password. Verified against Gradle 9.5.0: the same key
 *   resolves with `""` and does not with `null`, so an unset password secret becomes the empty string
 *   here. A *wrong* password or a truncated key block is already loud ("Could not read PGP secret
 *   key"), so there is nothing to paper over in those cases.
 * * **The POM is complete**: name, description, licence, SCM and developer, because Central rejects
 *   incomplete metadata and consumers read it in dependency insight reports.
 */

group = "io.github.cybersafetyid"
version = providers.gradleProperty("bluelib.version").getOrElse("1.0.0-SNAPSHOT")

// The root project's build directory, not this module's: Maven Central takes one bundle for the whole
// release, so all modules have to land in the same repository tree.
val stagingDirectory = rootProject.layout.buildDirectory.dir("staging-deploy")

extensions.configure<PublishingExtension>("publishing") {
    repositories {
        maven {
            name = "staging"
            url = uri(stagingDirectory)
        }
    }
}

val isAndroidLibrary = plugins.hasPlugin("com.android.library")

if (!isAndroidLibrary) {
    // Android modules get their sources jar through `singleVariant(…).withSourcesJar()`. JVM modules
    // need it here, and Central consumers expect sources to be published alongside the binaries.
    extensions.configure<JavaPluginExtension>("java") {
        withSourcesJar()
    }
}

afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("release") {
                if (isAndroidLibrary) {
                    from(components["release"])
                } else {
                    from(components["java"])
                }

                artifactId = project.name
                pom {
                    name.set("BlueLib ${project.name.removePrefix("bluelib-").replaceFirstChar { it.uppercase() }}")
                    description.set(
                        "Bluetooth library for Android 5.0 (API 21) through Android 17 (API 37): " +
                            "BLE scanning and advertising, GATT client and server, Bluetooth Classic, " +
                            "typed error handling and an explicit compatibility surface.",
                    )
                    url.set("https://github.com/cybersafetyid/BlueLib")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("cybersafetyid")
                            name.set("Cyber Safety ID")
                            url.set("https://github.com/cybersafetyid")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/cybersafetyid/BlueLib.git")
                        developerConnection.set("scm:git:ssh://git@github.com/cybersafetyid/BlueLib.git")
                        url.set("https://github.com/cybersafetyid/BlueLib")
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/cybersafetyid/BlueLib/issues")
                    }
                }
            }
        }
    }

    // `takeIf { isNotBlank() }` matters: an unset GitHub secret arrives as an empty string, and
    // treating that as "a key is configured" turns a clear "you forgot a secret" into an opaque
    // signing failure.
    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull?.takeIf { it.isNotBlank() }

    // Deliberately `?: ""` and deliberately *not* `takeIf { it.isNotBlank() }`: see the class comment.
    // A passphrase-less key is legitimate, and Gradle needs something non-null here either way.
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull ?: ""

    if (signingKey == null) {
        logger.lifecycle(
            "BlueLib: signing is not configured (no -PsigningInMemoryKey), so the staged artifacts " +
                "are unsigned. Maven Central only accepts signed artifacts; see docs/releasing.md.",
        )
    } else {
        extensions.configure<SigningExtension>("signing") {
            // The key is passed as an in-memory PGP block by CI; no keyring file is ever written.
            useInMemoryPgpKeys(signingKey, signingPassword)

            // Gradle reports neither a malformed key block nor a null password by throwing here; it
            // either fails much later or not at all. Resolving the signatory once, now, is the only
            // place we can turn that into a message that says what to fix.
            val resolved = runCatching { signatory }.getOrElse { cause ->
                throw GradleException(
                    "BlueLib: the signing key could not be read. The `signingInMemoryKey` value must be " +
                        "the whole ASCII-armored block, including the BEGIN and END lines. " +
                        "See docs/releasing.md.",
                    cause,
                )
            }
            if (resolved == null) {
                throw GradleException(
                    "BlueLib: the signing plugin produced no signatory for the supplied key, so the " +
                        "release would fail later with a message about the signing task instead of the " +
                        "key. Check that `signingInMemoryKey` is the complete ASCII-armored block from " +
                        "'gpg --armor --export-secret-keys <KEY_ID>'; see docs/releasing.md.",
                )
            }

            sign(extensions.getByType<PublishingExtension>().publications)
        }
        logger.lifecycle("BlueLib: signing with the supplied in-memory PGP key.")
    }
}
