plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Backend selector. Default = XNNPACK (CPU): pure Maven, always builds.
// Qualcomm QNN / Hexagon NPU: build with -PuseQnn=true after running
// scripts/setup_qnn_backend.sh (downloads the QNN AAR) and placing the QNN HTP
// runtime .so under app/src/main/jniLibs/arm64-v8a/. See
// docs/ondevice_vision_and_phone_haptics.md.
val useQnn = (project.findProperty("useQnn") as String?)?.toBoolean() ?: false

android {
    namespace = "com.sixthsense"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sixthsense"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The Galaxy S25 Ultra is arm64-only; drop the dead x86_64 ExecuTorch .so
        // and pre-align with the jniLibs/arm64-v8a layout the QNN .so libs need.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Active ExecuTorch backend, surfaced in the operator UI. Driven by -PuseQnn.
        buildConfigField("String", "EXECUTORCH_BACKEND", if (useQnn) "\"qnn\"" else "\"xnnpack\"")
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

    // Keep the ~400MB Qwen .pte uncompressed so it copies/loads fast from assets.
    androidResources {
        noCompress += "pte"
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

    // On-device OCR — ML Kit bundled Latin text-recognition model (no Play Services
    // download, works offline / airplane mode).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // WebSocket server for the dashboard bridge
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // ExecuTorch on-device runtime — backend chosen by -PuseQnn (see top of file).
    if (useQnn) {
        // Qualcomm QNN / Hexagon NPU. Requires: scripts/setup_qnn_backend.sh (downloads
        // app/libs/executorch-qnn-1.3.1.aar) AND the QNN HTP runtime .so (libQnnHtp.so,
        // the SM8750/Hexagon-v79 HTP skel, libQnnSystem.so, stubs) in
        // src/main/jniLibs/arm64-v8a/, AND a QNN-exported .pte. fbjni/soloader are not
        // transitive from a local AAR, so add them explicitly.
        implementation(files("libs/executorch-qnn-1.3.1.aar"))
        implementation("com.facebook.fbjni:fbjni:0.7.0")
        implementation("com.facebook.soloader:nativeloader:0.10.5")
    } else {
        // CPU / XNNPACK (default): pure Maven; pulls fbjni + soloader transitively.
        implementation("org.pytorch:executorch-android:1.3.1")
    }

    // Unit tests for the pure decoders + directional haptics encoding.
    testImplementation("junit:junit:4.13.2")
}
