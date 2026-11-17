import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "com.alexandretupinamba.posetrace"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alexandretupinamba.posetrace"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-spike"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraXVersion = "1.5.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    implementation("com.google.mediapipe:tasks-vision:1.2.2")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

val poseModelUrl =
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/" +
        "pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
val poseModelFile = file("src/main/assets/pose_landmarker_lite.task")

val downloadPoseModel by tasks.registering {
    group = "poseTrace"
    description = "Downloads the official MediaPipe Pose Landmarker Lite model."
    outputs.file(poseModelFile)

    doLast {
        if (!poseModelFile.exists() || poseModelFile.length() == 0L) {
            poseModelFile.parentFile.mkdirs()
            logger.lifecycle("Downloading Pose Landmarker Lite model...")
            URI(poseModelUrl).toURL().openStream().buffered().use { input ->
                poseModelFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadPoseModel)
}


protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
