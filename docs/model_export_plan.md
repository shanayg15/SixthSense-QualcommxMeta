# Model export plan

How models go from PyTorch to on-device `.pte` for SixthSense. **No model binaries are checked
into this repo** (see `.gitignore`).

## What is real in the starter repo

- The `SceneState` contract, `MockSceneProducer`, `BeltMapper`, BLE belt client, debug bridge,
  WebSocket, dashboard, and rule-based `VoiceAgent` — all real and runnable.
- `VisionPipeline` is a **documented placeholder**. It does not run any model yet and does not
  fake a finished ExecuTorch integration.

## What is expected later (model files)

| Function | Primary model | Fallback |
|---|---|---|
| Depth | Depth-Anything-V2 | smaller/lower-res depth, monocular approximation |
| Object detection | YOLOv8n / YOLOv11n | optional if depth is stable |
| OCR (on demand) | TrOCR | ML Kit on-device text recognition |
| Speech-to-text | Whisper base/small (quantized) | push-to-text for the demo |
| Agent | Llama 3.2 1B | rule-based answers from SceneState (already shipped) |
| TTS | Android offline TextToSpeech | prewritten phrases |

## Where `.pte` files go

Place compiled models under (git-ignored):

```text
android/app/src/main/assets/models/
  depth_anything_v2.pte
  yolo.pte
  trocr.pte        # or use ML Kit
  whisper.pte
  llama32_1b.pte
```

Load them from Android via the ExecuTorch runtime:

```kotlin
// implementation(files("libs/executorch.aar"))
val module = Module.load(assetFilePath("models/depth_anything_v2.pte"))
val output = module.forward(/* EValue inputs */)
```

## ExecuTorch integration TODO (in `VisionPipeline`)

1. Add the ExecuTorch Android runtime AAR to `app/libs/` and the `implementation(files(...))` dep.
2. CameraX `ImageAnalysis` → preprocess frame → `Module.forward` → postprocess.
3. Depth → inverse-depth → left/center/right nearness zones (ignore top third, 90th-percentile,
   temporal smoothing) → `DepthZones`.
4. YOLO box centers → zones; nearness from depth inside the box.
5. OCR only on the "read that sign" intent.
6. Emit `SceneState` onto the `SceneBus` at frame rate; log per-component latency + active backend.

## Qualcomm AI Hub export path (recommended from macOS)

Because we only have MacBooks, do QNN-specific export/build through **Qualcomm AI Hub** (cloud)
rather than fighting a native macOS QNN toolchain:

```bash
pip install qai-hub
qai-hub configure --api_token <YOUR_TOKEN>   # never commit the token
# submit a compile job targeting the S25 SoC, download the resulting .pte
```

Target device: **Samsung Galaxy S25 Ultra**, Snapdragon 8 Elite for Galaxy, **SM8750**,
Hexagon **v79**. Confirm exact `--soc_model` / backend flags with Qualcomm mentors.

The MCP `qaihub_status` tool checks `qai-hub` availability and recent jobs.

## QNN vs XNNPACK fallback

- **QNN (Qualcomm NPU)** — preferred: highest performance/efficiency on Snapdragon.
- **XNNPACK (CPU)** — universal fallback for any model that does not lower cleanly to QNN.
- Keep both paths available per model so the demo never hard-depends on a fragile QNN export.

## Honesty / calibration warning

Monocular depth gives **relative** nearness, not calibrated metric distance. **Do not claim true
metric depth without calibration.** Tune the belt `NEAR_THRESHOLD` empirically against a real
chair/wall on the actual course, and describe output as relative obstacle nearness.
