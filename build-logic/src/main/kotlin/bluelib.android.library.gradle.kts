import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.library")
}

// BlueLib supports Android 5.0 (API 21) through the newest release the toolchain knows about.
// AGP 9.1.1+ is required to compile against API 37.
extensions.configure<LibraryExtension>("android") {
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        // Only referenced when the module ships one: AGP fails the build for a missing consumer rules
        // file, and a module that needs no rules should not need an empty placeholder either.
        val consumerRules = project.file("consumer-rules.pro")
        if (consumerRules.exists()) {
            consumerProguardFiles(consumerRules)
        }
    }

    compileOptions {
        // Kotlin's jvmTarget follows this value under built-in Kotlin.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    lint {
        // NewApi / InlinedApi / MissingPermission keep their default (error) severity, so CI
        // fails whenever a platform call is not guarded by a version check.
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes.addAll(
            setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            ),
        )
    }
}

extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xexplicit-api=strict")
    }
}

dependencies {
    add("implementation", "androidx.annotation:annotation:1.9.1")
    add("testImplementation", kotlin("test"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.4")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

// Explicit API mode guards what is *shipped*; test sources are not part of the published surface, so
// they rank the missing visibility modifiers as warnings instead of errors.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("Test")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=warning")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
