# Hybrid runtime: Qualcomm AI Hub (LiteRT) for CV + ExecuTorch for the LLM

SixthSense runs **two on-device runtimes**, each where it's strongest:

| Path | Runtime | Models | Why |
|---|---|---|---|
| **Vision (depth + detection)** | **LiteRT** (`.tflite`) from **Qualcomm AI Hub**, on the **Hexagon NPU** (GPU/CPU fallback) | Depth-Anything-V2, YOLOv11 | AI Hub compiles/quantizes for the S25 from a Mac (no Linux box); NPU speed |
| **Voice agent (LLM)** | **ExecuTorch** (`.pte`) `LlmModule` | Qwen2.5-0.5B-Instruct | Keeps the ExecuTorch/Meta sponsor story; great on-device LLM runner |

Everything is offline / airplane-mode. The belt mapper, phone-haptics test mode, dashboard, and
`SceneState` contract are unchanged.

## 1. Export the CV models (Qualcomm AI Hub → LiteRT)

In the models repo (`~/sixthsense-models`), after a **free** AI Hub token
(`./.venv/bin/qai-hub configure --api_token <TOKEN>`, from https://app.aihub.qualcomm.com):

```bash
bash export_aihub_cv.sh        # depth_anything_v2 + yolov11_det -> models/tflite/{depth,yolo}.tflite
```

It compiles for **"Samsung Galaxy S25 Ultra"** (SM8750), quantizes (`w8a16` depth, `w8a8` yolo — the
NPU needs INT/fp16), profiles on a real cloud device, and downloads the `.tflite`. Hand them to the
app's `assets/models/` (git-ignored) — same out-of-band flow as the `.pte` files.

**Verified IO contract the Android decoders rely on** (differs from the ExecuTorch `.pte`!):
- TFLite is **NHWC**. Feed RGB in **[0,1]** (`/255`); **do not** apply ImageNet mean/std — depth bakes
  it into the graph.
- **Depth** output `[1,518,518,1]` → flattens to 518×518 inverse depth (larger = closer).
- **YOLO** output is **pre-decoded**: `boxes[1,8400,4]` (xyxy, 0..640) + `scores[1,8400]` +
  `class_idx[1,8400]`. App runs **NMS only** (no transpose/argmax/sigmoid).
- Quantized builds have **uint8 IO** with baked scale/zero-point; LiteRT's typed `writeFloat`/
  `readFloat` handle (de)quantization.

## 2. Android CV loader (LiteRT)

- `vision/LiteRtModel.kt` — `CompiledModel` with **NPU → GPU → CPU** fallback; loads `.tflite` straight
  from assets; multi-output `run()`.
- `vision/LiteRtFrameConverter.kt` — `ImageProxy` → NHWC float `[1,size,size,3]`, `/255`.
- `vision/LiteRtDecoders.kt` — `LiteRtYolo.decode()` for the 3-tensor output (NMS), falling back to the
  raw `[1,84,8400]` `YoloDecoder` if a non-default export is used. Depth reuses `DepthDecoder`.
- `vision/VisionPipeline.kt` — rewired to LiteRT; still streams frames to the dashboard, surfaces the
  live backend (`litert/npu|gpu|cpu`) + detection count, and degrades to mock mode with no models.

Gradle: `implementation("com.google.ai.edge.litert:litert:2.1.0")`. The ExecuTorch CV loader
(`EtModule`/`FrameToTensor`) was removed.

### NPU runtime libs
For Hexagon NPU acceleration, drop the Qualcomm AI Engine Direct / QNN HTP `.so` for SM8750 into
`app/src/main/jniLibs/arm64-v8a/` (`libQnnHtp.so`, `libQnnHtpV79Skel.so`, `libQnnSystem.so`, stubs) —
from `litert_npu_runtime_libraries.zip` or the QNN SDK (`bash scripts/setup_litert_npu.sh` for the
checklist). Without them, LiteRT runs the same `.tflite` on **GPU/CPU** and the app still works.

## 3. On-device LLM (ExecuTorch)

- `voice/LlmEngine.kt` — wraps `org.pytorch.executorch.extension.llm.LlmModule` (inside the 1.3.1 AAR);
  copies `qwen.pte` + `qwen-tokenizer/tokenizer.json` from assets to filesDir, `load()`, streaming
  `generate()`. Consumes the HF `tokenizer.json` directly. Loads in the background; **no model →
  rule-based answers** (graceful).
- `voice/VoiceAgent.kt` — `ask()` stays synchronous/rule-based; new `askAsync()` uses the LLM for
  open-ended "what's ahead?" questions, grounded in a scene summary (never invents objects), Qwen chat
  template, low temperature. The **Ask** button calls `askAsync`.

Drop `qwen.pte` + the `qwen-tokenizer/` folder into `assets/models/` to enable it.

## What still needs the human / device
- A **free AI Hub token** (I can't create it) to run `export_aihub_cv.sh`.
- A real **Android Studio build** on the S25 (no Android SDK on the dev Mac here).
- The **NPU `.so` libs** for true Hexagon acceleration (QNN SDK); GPU/CPU works without them.
- After exporting, verify the real `.tflite` IO with `tf.lite.Interpreter(...).get_*_details()` and
  adjust decoder tensor mapping if a non-default export shape appears.
