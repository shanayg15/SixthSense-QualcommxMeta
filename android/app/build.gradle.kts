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

        // The Galaxy S25 Ultra is arm64-only; drop the dead x86_64 ExecuTorch .so
        // and pre-align with the jniLibs/arm64-v8a layout the QNN .so libs need.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Active ExecuTorch backend, surfaced in the operator UI. The plain Maven
        // executorch-android AAR is XNNPACK(CPU). When swapping in the QNN AAR for
        // the Hexagon NPU build, change this to "qnn".
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

    // ExecuTorch on-device runtime.
    // CPU / XNNPACK path (ship now): pure Maven; pulls fbjni + soloader transitively.
    implementation("org.pytorch:executorch-android:1.3.1")

    // Qualcomm QNN / Hexagon NPU path (drop-in later — see
    // docs/ondevice_vision_and_phone_haptics.md). To switch:
    //  1) download the prebuilt QNN AAR to android/app/libs/executorch-qnn-1.3.1.aar:
    //     https://ossci-android.s3.amazonaws.com/executorch/release/1.3.1-qnn/executorch.aar
    //  2) REMOVE the Maven line above (two libexecutorch.so would clash) and use:
    //       implementation(files("libs/executorch-qnn-1.3.1.aar"))
    //       implementation("com.facebook.fbjni:fbjni:0.7.0")        // not transitive from a local AAR
    //       implementation("com.facebook.soloader:nativeloader:0.10.5")
    //  3) bundle the Qualcomm QNN HTP runtime .so (libQnnHtp.so, the SM8750/Hexagon-v79
    //     HTP skel, libQnnSystem.so, stubs) under src/main/jniLibs/arm64-v8a/
    //  4) flip EXECUTORCH_BACKEND above to "qnn" and ship a QNN-exported .pte.

    // Unit tests for the pure decoders + directional haptics encoding.
    testImplementation("junit:junit:4.13.2")
}
