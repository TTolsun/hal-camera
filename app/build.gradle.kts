import java.util.Properties
import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material stays out of the repository. Create keystore.properties next to
// settings.gradle.kts with storeFile, storePassword, keyAlias and keyPassword to build a signed
// release. Without that file the release build is produced unsigned, so a debug build and CI still
// work on a machine that does not hold the key.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "dev.halcamera"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.halcamera"
        minSdk = 26
        targetSdk = 36
        versionCode = 691
        versionName = "0.30.0"
        testInstrumentationRunner = "dev.halcamera.cli.CliStoreInstrumentation"
    }
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
    sourceSets.getByName("androidTest").assets.srcDir("../tools/halcam/fixtures")
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/depthAssets"))
    androidResources { noCompress += "tflite" }
}

// Official, MIT-licensed MiDaS v2.1 mobile model. Never accept an unverified download.
val depthModel = layout.buildDirectory.file("generated/depthAssets/midas-v2_1.tflite")
val prepareDepthModel by tasks.registering {
    outputs.file(depthModel)
    val expected = "93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd"
    fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    outputs.upToDateWhen { depthModel.get().asFile.let { it.exists() && hash(it) == expected } }
    doLast {
        val destination = depthModel.get().asFile
        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, "model.download")
        try {
            val connection = URI("https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite").toURL().openConnection()
            connection.connectTimeout = 30000; connection.readTimeout = 120000
            connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(hash(temporary) == expected) { "MiDaS model SHA-256 mismatch" }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareDepthModel) }
dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    implementation(project(":ctsvendor"))
    testImplementation("junit:junit:4.13.2")
}
