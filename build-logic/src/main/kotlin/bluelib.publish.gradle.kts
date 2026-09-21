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
 * * **Signing is opt-in.** `signAllPublications()` is only configured when a key is present, so
 *   `./gradlew build` works for contributors who have no PGP key — CI does have one, and the release
 *   workflow asserts that signatures exist before uploading.
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

    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
    if (signingKey != null) {
        extensions.configure<SigningExtension>("signing") {
            // The key is passed as an in-memory PGP block by CI; no keyring file is ever written.
            useInMemoryPgpKeys(
                signingKey,
                providers.gradleProperty("signingInMemoryKeyPassword").orNull,
            )
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    }
}
