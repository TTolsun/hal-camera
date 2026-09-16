plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Vendored AOSP CTS camera tests (see UPSTREAM.md). The Java sources under src/main/java/android and
// src/main/java/com/android are upstream files with the patches listed in UPSTREAM.md; everything under
// dev/halcamera/ctsvendor and androidx/test is ours and makes those files run inside the app process.
android {
    namespace = "dev.halcamera.ctsvendor"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = true
        lintConfig = file("lint.xml")
    }
}

dependencies {
    // JUnit 4 runs inside the app: the vendored tests are plain JUnit classes driven by JUnitCore.
    api("junit:junit:4.13.2")
    implementation("androidx.annotation:annotation:1.9.1")
    // CameraTestUtils and CameraSessionUtils reference Mockito in helpers other tests use; mockito-android
    // provides the subclass mock maker that works without an instrumentation agent.
    implementation("org.mockito:mockito-android:5.14.2")
}
