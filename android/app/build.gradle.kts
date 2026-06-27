plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sixthsense"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sixthsense"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The Galaxy S25 Ultra is arm64-only; drops dead x86_64 libs and matches the
        // jniLibs/arm64-v8a layout the Qualcomm NPU .so libs need.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // CV backend (Qualcomm AI Hub LiteRT, NPU→GPU→CPU) surfaced in the operator UI;
        // the live value is refined to litert/npu|gpu|cpu at model load.
        buildConfigField("String", "CV_BACKEND", "\"litert-aihub\"")
        // The on-device LLM stays on ExecuTorch (XNNPACK).
        buildConfigField("String", "EXECUTORCH_BACKEND", "\"xnnpack\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Debug-only broadcast receiver lives in src/debug — see CLAUDE.md.
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX (used by VisionPipeline once the real pipeline is wired up)
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // WebSocket server for the dashboard bridge
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // CV backend: Qualcomm AI Hub models via LiteRT (CompiledModel, NPU→GPU→CPU).
    // For Hexagon NPU acceleration, ship the Qualcomm AI Engine Direct / QNN HTP
    // runtime .so (libQnnHtp.so, libQnnHtpV79Skel.so for SM8750, libQnnSystem.so)
    // in src/main/jniLibs/arm64-v8a/ (from litert_npu_runtime_libraries.zip or the
    // QNN SDK); without them LiteRT falls back to GPU/CPU and the app still runs.
    implementation("com.google.ai.edge.litert:litert:2.1.0")

    // On-device LLM (voice agent) hosted with ExecuTorch — LlmModule is inside this
    // single AAR (XNNPACK); pulls fbjni/soloader/core-ktx transitively.
    implementation("org.pytorch:executorch-android:1.3.1")

    // JVM unit tests for the pure decoders + directional haptics encoding.
    testImplementation("junit:junit:4.13.2")
}
