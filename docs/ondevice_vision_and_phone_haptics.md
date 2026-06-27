# On-device NPU vision + directional phone-haptics test mode

This documents the two capabilities added on top of the SixthSense starter: a real,
fully **on-device** vision pipeline (CameraX + ExecuTorch) and a **directional
phone-vibration test mode** that needs no belt and no BLE.

## 1. On-device vision pipeline

`VisionPipeline` (`android/app/src/main/java/com/sixthsense/vision/`) binds CameraX and runs
ExecuTorch `.pte` models locally — **no network, airplane-mode capable** — and publishes
`SceneState` onto the `SceneBus` that the belt mapper, voice agent, dashboard, and phone-haptics
test mode all read.

```
CameraX ImageAnalysis (RGBA_8888, KEEP_ONLY_LATEST)
  → FrameToTensor (rotate, square-resize, ImageNet / 1-over-255 norm, CHW)
  → EtModule.forward  (org.pytorch:executorch-android 1.3.1)
      Depth-Anything-V2  → DepthDecoder.toZones  → DepthZones (L/C/R nearness, curb)
      YOLOv11n           → YoloDecoder.decode + NMS + SceneAssembler → List<DetectedObj>
  → SceneState(depth, objects, pathClear, conf, belt) → SceneBus
```

Decoders (`Decoders.kt`) are **pure Kotlin** (no Android imports) and unit-tested on the JVM
(`src/test/.../DecodersTest.kt`). Depth-Anything outputs **inverse relative** depth (larger =
closer), normalized within each frame; YOLOv11n raw output is `[1,84,8400]` channel-major
(4 bbox + 80 COCO scores, no objectness).

### Dropping in the models

`.pte` files are **git-ignored** and shared out of band (e.g. from Stream B). Place them under:

```
android/app/src/main/assets/models/
  depth.pte     (or depth_anything_v2.pte / depth_anything_v2_small.pte)
  yolo.pte      (or yolo11n.pte / yolov11n.pte)
```

The pipeline tries those candidate names and **degrades gracefully**: with no depth model it stays
idle (use Mock mode); with depth but no YOLO it runs depth-only (belt works, no object labels). The
app builds and runs with zero `.pte` present.

### Running it

Tap **Start Live Vision** (grant CAMERA). The operator status line shows the active backend, per-
model latency, and fps. CPU/XNNPACK is slow on purpose (~1–2 fps) — the NPU swap below fixes speed.

## 2. CPU now, Qualcomm NPU later (drop-in)

The ExecuTorch backend is baked into the `.pte` at **export time**; the Kotlin is identical for
CPU and NPU. Today we ship the Maven `org.pytorch:executorch-android:1.3.1` AAR (XNNPACK/CPU). To
move inference onto the **Hexagon NPU** on the Galaxy S25 Ultra (SM8750):

1. Download the prebuilt QNN AAR to `android/app/libs/executorch-qnn-1.3.1.aar`:
   `https://ossci-android.s3.amazonaws.com/executorch/release/1.3.1-qnn/executorch.aar`
2. In `app/build.gradle.kts`, **remove** the Maven `executorch-android` line (two
   `libexecutorch.so` would clash) and add:
   ```kotlin
   implementation(files("libs/executorch-qnn-1.3.1.aar"))
   implementation("com.facebook.fbjni:fbjni:0.7.0")          // not transitive from a local AAR
   implementation("com.facebook.soloader:nativeloader:0.10.5")
   ```
3. Bundle the Qualcomm QNN HTP runtime `.so` (`libQnnHtp.so`, the SM8750/Hexagon-v79 HTP skel,
   `libQnnSystem.so`, stubs) under `app/src/main/jniLibs/arm64-v8a/`.
4. Flip `EXECUTORCH_BACKEND` to `"qnn"` in `build.gradle.kts` and ship a **QNN-exported** `.pte`
   (export with `QnnPartitioner` + HTP compiler spec for SM8750 on the Linux box).

No app-code change is needed — same `Module.load` / `forward`.

## 3. Directional phone-haptics test mode

A test mode where the **phone's own vibration motor** conveys obstacle direction, so you can feel
the system with **no external hardware** — hold the phone near the waist and an obstacle on the
left/center/right buzzes with a distinct rhythm.

- `DirectionalEncoding` (pure, unit-tested) maps a belt packet `[L,C,R,pattern]` to a single-actuator
  signature. Phones have one motor (no true spatial L/R), so direction is encoded **temporally**:
  **LEFT** = short→long, **RIGHT** = long→short (mirror), **CENTER** = steady block,
  **CURB** (pattern 2) = hard triple thump, **CAUTION** (pattern 1) = soft directionless pulse.
  Intensity rides on amplitude (with a perceptible floor for felt-through-clothing).
- `PhoneHapticsActuator` resolves the system `Vibrator` (`VibratorManager.getDefaultVibrator()` on
  API 31+, else `VIBRATOR_SERVICE`) and plays a repeating waveform; it de-dupes identical packets
  and falls back to a timing-only rhythm if the device lacks amplitude control.
- `PhoneHapticsController` is the test mode: when enabled it mirrors the live `SceneBus` to the
  motor via `BeltMapper.packetAsInts(scene)` — the **same packet the BLE belt uses**.

Requires `android.permission.VIBRATE` (added to the manifest).

### Running it

Tap **Phone Haptics Test Mode** to toggle it on, then drive scenes via **Start Live Vision** or
**Mock On**. Over adb (debug build) you can fire a directional buzz with no camera/BLE:

```bash
adb shell am broadcast -a com.sixthsense.DEBUG_HAPTICS --ez enabled true
adb shell am broadcast -a com.sixthsense.DEBUG_BELT --ei l 200 --ei c 0 --ei r 0 --ei p 0  # left
adb shell am broadcast -a com.sixthsense.DEBUG_BELT --ei l 0 --ei c 0 --ei r 200 --ei p 0  # right
adb shell am broadcast -a com.sixthsense.DEBUG_BELT --ei l 0 --ei c 180 --ei r 0 --ei p 2  # curb
```

(The `DEBUG_BELT` broadcast also fires the phone motor directly, independent of test-mode state.)
