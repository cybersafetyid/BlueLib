plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.cybersafetyid.bluelib.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.cybersafetyid.bluelib.sample"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Published library from Maven Central
    implementation("io.github.cybersafetyid:bluelib:0.1.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
