import io.github.cybersafetyid.buildlogic.api.GenerateCompatibilityMatrixTask
import java.io.File

/**
 * Registers `generateCompatibilityMatrix` on the project this plugin is applied to (the root project
 * of the BlueLib build).
 */
val compileSdkLevel = 37

tasks.register<GenerateCompatibilityMatrixTask>("generateCompatibilityMatrix") {
    group = "documentation"
    description = "Regenerates docs/compatibility-matrix.md from the Android SDK api-versions.xml"

    targetApiLevel.set(compileSdkLevel)
    val sdkDir = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: File(System.getProperty("user.home"), "AppData/Local/Android/sdk").path
    // A missing file only fails when the task runs, which keeps configuration working on machines
    // without the Android SDK (for example a docs-only CI job).
    apiVersionsXml.set(File(sdkDir, "platforms/android-$compileSdkLevel.0/data/api-versions.xml"))
    outputFile.set(layout.projectDirectory.file("docs/compatibility-matrix.md"))
}
