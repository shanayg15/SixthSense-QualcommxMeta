#!/usr/bin/env bash
# Checklist for enabling Qualcomm Hexagon NPU acceleration for the LiteRT CV models
# on the Galaxy S25 Ultra (SM8750 / Snapdragon 8 Elite, Hexagon v79).
#
# The app runs the Qualcomm AI Hub .tflite on GPU/CPU out of the box. For the NPU,
# LiteRT's CompiledModel(Accelerator.NPU) needs the Qualcomm AI Engine Direct / QNN
# HTP runtime .so libraries bundled in the APK. These come from the Qualcomm QNN SDK
# or Google's litert_npu_runtime_libraries package and cannot be fetched from a single
# public URL, so this script only documents the steps.
set -euo pipefail

JNILIBS="android/app/src/main/jniLibs/arm64-v8a"
echo "Target NPU lib dir: $JNILIBS"
echo
echo "1) Obtain the Qualcomm NPU runtime libs (one of):"
echo "   - Google LiteRT: litert_npu_runtime_libraries.zip (or _jit.zip)"
echo "   - Qualcomm QNN SDK: \$QNN_SDK_ROOT/lib/aarch64-android + lib/hexagon-v79/unsigned"
echo
echo "2) Copy these into $JNILIBS (Hexagon v79 for SM8750):"
echo "     libQnnHtp.so"
echo "     libQnnHtpV79Skel.so      # MUST match the SoC Hexagon version (v79 = 8 Elite)"
echo "     libQnnHtpPrepare.so"
echo "     libQnnSystem.so          # + any stub libs shipped with your QNN version"
echo
echo "3) Drop the AI Hub .tflite models into android/app/src/main/assets/models/"
echo "   (depth.tflite, yolo.tflite — from ~/sixthsense-models/export_aihub_cv.sh)"
echo
echo "4) Build + install in Android Studio; the operator status shows 'litert/npu' when"
echo "   the Hexagon NPU bound (else 'litert/gpu' or 'litert/cpu')."
echo
echo "Note: keep the QNN runtime .so, the skel version, and any delegate in lockstep —"
echo "mismatched versions silently fall back to CPU/GPU or fail delegate init."
mkdir -p "$JNILIBS" 2>/dev/null || true
echo
echo "Created $JNILIBS (empty; add the .so libs above). It is git-ignored."
