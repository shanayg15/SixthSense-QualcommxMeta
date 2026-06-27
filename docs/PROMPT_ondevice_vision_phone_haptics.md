# Claude Code (Opus, ultracode) — Execution Prompt: On-device NPU vision + directional phone-haptics test mode

> This is a self-contained, execution-ready spec for completing SixthSense's on-device perception
> and adding a no-hardware phone-vibration test mode. It is grounded in **verified** ExecuTorch
> 1.3.1 Android API facts (decompiled from the real AAR), the current CameraX RGBA path, the
> Android `Vibrator`/`VibratorManager` API, and the exact YOLOv11 / Depth-Anything-V2 output
> layouts. Execute it end to end. Work autonomously; do not stop unless a hard precondition fails.

---

## 0. CONTEXT

SixthSense is a privacy-first, **fully on-device** navigation copilot for blind / low-vision users.
A chest-mounted **Samsung Galaxy S25 Ultra** (Snapdragon 8 Elite, **SM8750**, Hexagon NPU) runs
CameraX + ExecuTorch models locally and produces one contract — `SceneState` — that drives a haptic
belt, a voice agent, and a visualization dashboard. **Everything must work in airplane mode. No
cloud, no external LLM, no Claude in the assistive runtime.**

Repo: `~/SixthSense-`, Android app under `android/` (Kotlin, package `com.sixthsense`, minSdk 26,
targetSdk 35, compileSdk 35, JDK 17). The `SceneState` contract, `SceneBus` (StateFlow),
`BeltMapper`, `MockSceneProducer`, `BeltClient` (BLE), `VoiceAgent`, `SceneSocket`, and the operator
`MainActivity` are all **real and frozen** — do not change their public contracts. `VisionPipeline`
is a **no-op placeholder**; it is the main thing to complete.

## 1. GOAL (two deliverables)

**A. Fully complete the on-device image-processing / AI pipeline using the Qualcomm NPU.**
Real `VisionPipeline`: CameraX live frames → ExecuTorch `.pte` inference (depth + object detection)
→ fill `SceneState` on the bus at frame rate. All perception + AI hosted **on device** on the
Snapdragon; QNN/Hexagon-NPU accelerated where available, XNNPACK/CPU fallback per model, identical
app code for both backends. Airplane-mode capable.

**B. A directional phone-vibration TEST MODE.**
A test mode where the **phone's own vibration motor** gives **directional** feedback: hold the phone
near the waist; when an obstacle is detected on the left / center / right, the phone vibrates in a
way that conveys that direction (and intensity). No external hardware (no ESP32, no BLE belt). This
is both a developer test harness and a genuine zero-hardware fallback for the demo.

## 2. NON-NEGOTIABLE CONSTRAINTS

- **On-device only.** No network calls, no cloud, no external LLM in the perception/haptics path.
- **Do not break the frozen contracts**: `SceneState`, `DepthZones`, `DetectedObj`, `SceneBus`,
  `BeltMapper.packetAsInts` (returns `[L, C, R, pattern]`).
- **Never commit model binaries.** `.pte`/`.pt`/`.bin` and `android/app/src/main/assets/models/`
  are already gitignored — keep it that way.
- **Graceful degradation:** the app must still build and run with **no `.pte` files present**
  (dev machines won't have them). Missing/failed model load → log and fall back (depth-only, or to
  mock mode) — never crash, never emit a confidently-wrong "all clear."
- **Backend-agnostic code:** the ExecuTorch backend is baked into the `.pte` at export time; the
  Kotlin must be byte-for-byte identical for XNNPACK(CPU) and QNN(NPU). A QNN `.pte` is a drop-in.
- **Git/commits:** author is **shanayg15 only — NO Claude co-authorship**, no "Generated with"
  trailer. Commit on a feature branch.

## 3. VERIFIED API REFERENCE (do not re-research; these are decompiled/confirmed)

### 3.1 ExecuTorch Android (runtime)
- Gradle (CPU/XNNPACK, ship now): `implementation("org.pytorch:executorch-android:1.3.1")`
  (pulls fbjni 0.7.0 + soloader 0.10.5 transitively). Add `ndk { abiFilters += "arm64-v8a" }`
  (S25 is arm64-only; drops dead x86_64 `.so`).
- QNN/NPU (later, drop-in): download
  `https://ossci-android.s3.amazonaws.com/executorch/release/1.3.1-qnn/executorch.aar` →
  `android/app/libs/`, swap to `implementation(files("libs/executorch-qnn-1.3.1.aar"))` (REMOVE the
  Maven line so two `libexecutorch.so` don't clash), re-add `fbjni:0.7.0` + `soloader:0.10.5`
  explicitly (a local AAR has no transitive POM), and bundle the Qualcomm QNN HTP runtime `.so`
  (`libQnnHtp.so`, the SM8750/Hexagon-v79 HTP skel, `libQnnSystem.so`, stubs) in
  `src/main/jniLibs/arm64-v8a/`. The Linux box is only needed to **export** the QNN `.pte`, not for
  the AAR.
- Package `org.pytorch.executorch`. Signatures (verified via javap on 1.3.1):
  - `Module.load(String path)` / `Module.load(path, Module.LOAD_MODE_MMAP)`; `LOAD_MODE_*` consts.
  - `module.forward(EValue... ): EValue[]`; `module.execute(name, EValue...)`; `module.destroy()`.
  - `Tensor.fromBlob(float[] data, long[] shape)` and a direct-`FloatBuffer` overload; `Tensor.shape()`
    (**NOT `getShape()` — does not exist**); `tensor.getDataAsFloatArray()` (Kotlin: `.dataAsFloatArray`).
  - `EValue.from(tensor)`, `outputs[0].toTensor()`.
- **Asset loading:** `Module.load` needs a real filesystem path. A `.pte` shipped in
  `src/main/assets/` must be **copied to `context.filesDir` once** and the absolute path passed in
  (guard with an `exists()/length()` check; do it off the main thread).

### 3.2 CameraX → tensor
- `ImageAnalysis.Builder().setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
  .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)` — RGBA lets CameraX do YUV→RGB; one used
  plane (`planes[0]`), **handle row padding** `rowPadding = rowStride - pixelStride*width`, route
  through `Bitmap.copyPixelsFromBuffer` (don't hand-index the buffer — the docs' byte-order list is
  misleading; RGBA is R,G,B,A in memory).
- Apply `imageProxy.imageInfo.rotationDegrees` **before** resize. **Always** `imageProxy.close()`
  in `finally` (or KEEP_ONLY_LATEST stalls). Analyzer runs on a single-thread `Executor` — never on
  main. Bind `Preview` + `ImageAnalysis` together via `ProcessCameraProvider.bindToLifecycle(...)`
  on the main thread.
- Preprocess: **Depth-Anything** = ImageNet norm (mean `[.485,.456,.406]`, std `[.229,.224,.225]`)
  on 0..1 RGB, input `1x3x518x518`. **YOLOv11n** = plain `/255`, input `1x3x640x640`. Layout CHW
  (all R, then G, then B). Use a reused direct `FloatBuffer` per analyzer thread to kill GC churn.
  Use plain square resize (stretch) for both so box↔depth coordinate mapping stays a simple ratio.

### 3.3 Model output decode
- **YOLOv11n** raw output `[1, 84, 8400]`, **channel-major**: `value(attr, anchor) = flat[attr*8400
  + anchor]`. 84 = 4 bbox (cx,cy,w,h in 0..640 input pixels) + 80 COCO class scores. **No
  objectness.** confidence = max class score; classId = its argmax. Scores already sigmoid'd — don't
  re-activate. Keep conf ≥ 0.25; cxcywh→xyxy; class-agnostic NMS @ IoU 0.45. 8400 is 640-specific.
- **Depth-Anything-V2-Small** output flat length `518*518` (row-major `row*518+col`); it is
  **inverse relative depth — LARGER = CLOSER**, unitless, per-frame scale. **Normalize within the
  frame** (never a fixed constant). Use the lower two-thirds rows (walking space); per-zone
  (left/center/right thirds) take the ~90th percentile of inverse depth, then normalize 0..1 across
  the three bands. `curbAhead` = vertical-gradient spike in the center-bottom region. Object
  nearness = inverse depth sampled inside the box (rescale box 640→518 first).
- Keep all decoders **pure Kotlin (no Android imports)** so they JVM-unit-test with synthetic arrays.

### 3.4 Phone haptics (single actuator, directional)
- `VibratorManager` is API 31+ (`VIBRATOR_MANAGER_SERVICE`, `getDefaultVibrator()`); on 26–30 use
  `VIBRATOR_SERVICE`. Drive both with the same `Vibrator` API. `VibrationEffect.createWaveform(long[]
  timings, int[] amplitudes, int repeat)` — amplitude 1..255 (0 = OFF segment), `repeat` = loop
  start index (≥0 loops until `cancel()`). `hasAmplitudeControl()` may be false → fall back to a
  timing-only (on/off) waveform so direction still reads.
- **No phone has separate L/R motors** (S25 `getVibratorIds().size == 1`) → encode direction
  **temporally** on the one motor: **LEFT = short-pip→long-buzz, RIGHT = long-buzz→short-pip
  (mirror), CENTER = one steady block, CURB(pattern 2) = hard triple thump @ full, caution(pattern
  1) = single soft pulse with NO direction.** Pick the **dominant** zone (max of L/C/R) per update.
  De-dupe identical consecutive packets (don't restart the loop every frame); `cancel()` + re-`vibrate()`
  to update. Map intensity→amplitude with a perceptible floor (~60) since it's felt through clothing.
- Add `<uses-permission android:name="android.permission.VIBRATE" />` (currently absent → vibrations
  silently no-op). No foreground service needed for a foreground Activity. Drive it from the **same
  packet** as the belt: `BeltMapper.packetAsInts(scene)`.

## 4. WORK PLAN (file by file)

1. `android/app/build.gradle.kts`: add ExecuTorch 1.3.1 (Maven/XNNPACK now) + `abiFilters
   "arm64-v8a"` + JUnit `testImplementation`; leave a commented, copy-paste-ready QNN-AAR swap
   block. Keep CameraX deps.
2. `android/app/src/main/AndroidManifest.xml`: add `VIBRATE`. (CAMERA already declared.)
3. `vision/EtModule.kt`: backend-agnostic ExecuTorch wrapper — try a list of candidate asset names
   (`models/depth.pte`, `models/depth_anything_v2*.pte`; `models/yolo.pte`, `models/yolo11n.pte`),
   copy to filesDir, `Module.load`, `runForward(FloatBuffer, LongArray)→FloatArray` (+ out shape),
   `close()`. Returns `null`/throws-caught when no asset present so the pipeline degrades.
4. `vision/FrameToTensor.kt`: `ImageProxy(RGBA_8888)`→normalized CHW direct-`FloatBuffer` `Tensor`
   (rotation, plain square resize, RGB order, per-model norm). One instance per analyzer thread.
5. `vision/Decoders.kt`: `COCO_LABELS`, `RawDet`, `YoloDecoder` (decode + NMS + zone), `DepthDecoder`
   (toZones + frameRange + nearnessInBox + curbAhead), `SceneAssembler` (YOLO+depth→`List<DetectedObj>`).
   Pure Kotlin.
6. `vision/VisionPipeline.kt`: REWRITE. `start(lifecycleOwner, previewView?)` binds CameraX; per
   frame run depth→zones (+ YOLO→objects when its model is present, optionally every Nth frame),
   assemble `SceneState(depth, objects, pathClear = center<thr && !curb, conf)` and `bus.emit`.
   Log active backend + per-model latency + fps. If no models: log "no models — use mock mode" and
   don't emit garbage. `stop()` unbinds + closes modules + shuts the executor.
7. `haptics/DirectionalEncoding.kt`: **pure** packet→(direction, amplitude, waveform timings/amps)
   logic (JVM-testable).
8. `haptics/PhoneHapticsActuator.kt`: resolves the `Vibrator`, turns `DirectionalEncoding` output
   into `VibrationEffect`, de-dupes, `onBeltPacket(List<Int>)`, `stop()`, `hasVibrator()`,
   `hasAmplitudeControl` fallback.
9. `haptics/PhoneHapticsController.kt`: the TEST MODE — `setEnabled(Boolean)`; when on, collects
   `SceneBus.state` and forwards `BeltMapper.packetAsInts(scene)` to the actuator; when off, `stop()`.
10. `debug/AppGraph.kt`: construct + hold `visionPipeline`, `phoneHaptics` (controller + actuator).
11. `MainActivity.kt`: add a `PreviewView`; CAMERA permission; "Start/Stop Live Vision" → pipeline;
    "Phone Haptics Test Mode" toggle → controller; show backend/latency/fps in the readout.
12. `res/values/strings.xml`: new button strings.
13. `debug/DebugReceiver.kt` (debug variant): add `DEBUG_HAPTICS` (extra `enabled`) and make
    `DEBUG_BELT` also drive the phone actuator, so directional buzz is adb-testable with no camera/BLE.
14. `src/test/java/com/sixthsense/...`: JVM unit tests for `Decoders` (synthetic `[1,84,8400]`,
    synthetic inverse-depth) and `DirectionalEncoding` (L/C/R/curb/caution mapping).
15. `docs/ondevice_vision_and_phone_haptics.md`: how to drop `.pte` files in, enable test mode, and
    swap to the QNN AAR for the NPU.

## 5. DEFINITION OF DONE (acceptance criteria)

- [ ] **Image processing + AI fully hosted on device** (Snapdragon): `VisionPipeline` runs CameraX
      + ExecuTorch depth (and YOLO when present) locally, fills `SceneState`, airplane-mode capable;
      QNN-NPU `.pte` is a verified drop-in (documented), XNNPACK CPU is the default now.
- [ ] **Directional phone test mode**: hold the phone near the waist, enable test mode; when an
      obstacle is detected the phone vibrates with the correct **directional** signature (left vs
      center vs right distinguishable, intensity scaled), reusing the belt packet. Works with **no
      external hardware**.
- [ ] App **builds and runs with zero `.pte` files** (graceful fallback to mock).
- [ ] No model binaries staged for git; on-device-only respected; frozen contracts intact.
- [ ] Pure decoders + directional encoding covered by JVM unit tests.
- [ ] Committed on a feature branch, **shanayg15 authorship only, no Claude co-author**.

## 6. VERIFICATION

- Static: the code must compile against the verified 1.3.1 signatures (`Tensor.shape()` not
  `getShape()`; `forward(): EValue[]`; `EValue.from`/`toTensor`). Run an adversarial compile-review.
- Logic: run the JVM unit tests for decoders + directional encoding (in Android Studio / CI if the
  Android SDK isn't on this machine).
- On-device (team, in Android Studio): `assembleDebug`, install on the S25, drop `depth.pte`/`yolo.pte`
  into `assets/models/`, Start Live Vision, enable Phone Haptics Test Mode, walk an obstacle and feel
  the directional buzz; confirm the operator readout shows the active backend + latency + fps.
