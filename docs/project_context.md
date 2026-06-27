# SixthSense — Project Context

**Event:** ExecuTorch Hackathon with Meta & Qualcomm, The Web Data Loft, San Francisco
**Dates:** June 27–28, 2026
**One-line concept:** An on-device navigation copilot for blind and low-vision users using a
chest-mounted Galaxy S25 Ultra camera, Snapdragon NPU vision models, an on-device voice agent,
and a haptic belt — all without internet.

> A more exhaustive narrative (with the extracted ExecuTorch developer-guide screenshots and
> purchase-history notes) lives in the repo's original source notes. This file is the
> repo-resident summary.

## Project idea

The phone is mounted on the user's chest facing forward. CameraX frames are processed locally
on the Snapdragon-powered S25 Ultra using ExecuTorch (Qualcomm QNN where possible, XNNPACK/CPU
fallback). The live scene is compressed into a compact `SceneState` (depth zones, objects, OCR,
confidence, path-clear). That state drives three outputs:

1. **Haptic belt** — left/center/right vibration motors signal obstacle direction, nearness,
   clear path, and curbs via intensity and pulse pattern. The belt is a *dumb actuator*.
2. **Voice agent** — push-to-talk on-device assistant ("what's ahead?", "read that sign",
   "find the exit") using STT → intent → reasoning → offline TTS.
3. **Dashboard** — a laptop visualization of the phone's live state and belt command.
   Visualization only; it runs no AI.

Thesis: **navigation safety should not depend on cloud connectivity.** SixthSense runs in
airplane mode, avoids network latency, preserves privacy, and works in tunnels/basements/dense
venues where cloud apps fail.

## Why on-device (sponsor fit)

- **Latency** — a one-second delay is dangerous while walking.
- **Connectivity** — cloud fails in basements, tunnels, elevators, crowded halls.
- **Privacy** — navigation video can include the user's home, faces, documents, bystanders.
- **Energy/cost** — Snapdragon NPU makes continuous local inference practical.

ExecuTorch (Meta/PyTorch) + Qualcomm QNN on the Galaxy S25 Ultra (Snapdragon 8 Elite for
Galaxy, **SM8750**, Hexagon **v79**) is exactly the target this event rewards.

## Hardware list

Must-have for the demo:
- 1× ESP32 board with BLE
- 3× vibration motors / modules
- 1× motor driver (**ULN2803A** Darlington array, transistor array, or MOSFETs)
- 1× USB power bank / safe battery supply
- Chest harness + phone clamp
- Belt / Velcro / elastic strap, wires, tape/heat-shrink
- MacBook with Android Studio
- Data-capable USB-C cable

Nice-to-have: 5–8 motors, spare ESP32, soldering iron, multimeter, travel router/hotspot,
scrcpy, nRF Connect, pre-recorded backup video.

### Ordered-hardware status & motor-delay risk
Ordered items include ESP32 boards, mini breadboards/jumper wires, a chest harness, Velcro
straps, and **vibration motor modules**. The vibration modules were scheduled to arrive
**July 2–3, after the event** — the single biggest hardware risk. **Source local/same-day
backup motors immediately** (ERM coin motors 3–3.3V, a local electronics store, or salvaged
motors). If no motors arrive, phone vibration is a weak last resort.

## Architecture

```text
Galaxy S25 Ultra (chest-mounted, airplane mode during demo)
  CameraX frames
    → VisionPipeline (ExecuTorch): depth (Depth-Anything-V2), detection (YOLO), OCR on demand
      → SceneState (depth zones, objects, pathClear, ocr, conf, belt)
          ├── BeltMapper → BeltClient (BLE) → ESP32 belt → 3 motors
          ├── VoiceAgent (Whisper → intent → Llama → TTS), push-to-talk
          ├── SafetyLayer (cautious output on low confidence)
          └── SceneSocket (WebSocket :8080) → laptop dashboard (visualize only)
```

The most important boundary is the **`SceneState` contract**: every component produces or
consumes it, and mock mode emits the same contract — so the belt, voice, and dashboard can be
built and demoed before the real models work.

## SceneState contract

```kotlin
data class DepthZones(val left: Float, val center: Float, val right: Float,
                      val curbAhead: Boolean = false, val stepDown: Boolean = false)
data class DetectedObj(val label: String, val zone: String, val nearness: Float, val conf: Float)
data class Ocr(val present: Boolean = false, val text: String = "")
data class SceneState(val ts: Long, val depth: DepthZones, val objects: List<DetectedObj>,
                      val pathClear: Boolean, val ocr: Ocr = Ocr(), val conf: Float,
                      val belt: List<Int> = emptyList())
```

Belt packet `[left, center, right, pattern]`; pattern 0 = steady, 1 = caution pulse,
2 = double pulse (curb/step). See [../firmware/esp32_belt/README.md](../firmware/esp32_belt/README.md).

## Software stack

- **Android** — Kotlin, CameraX, coroutines, Gson, Java-WebSocket, ExecuTorch runtime AAR.
- **Models** — Depth-Anything-V2, YOLOv8n/11n, TrOCR or ML Kit OCR, Whisper, Llama 3.2 1B,
  Android offline TTS. Exported to `.pte` (QNN preferred, XNNPACK fallback).
- **Firmware** — ESP32 Arduino core + NimBLE-Arduino.
- **Dashboard** — React + Vite + TypeScript.
- **Dev tooling** — Python FastMCP server + adb; Claude Code as a dev/debug command center.

## MVP ladder

1. **Depth/obstacle → belt** (must ship) — depth zones drive belt; airplane-mode demo works.
2. **Voice "what's ahead?"** — push-to-talk, on-device STT, answer from SceneState, TTS.
3. **OCR "read that sign"** — on-demand OCR; "find the exit" via EXIT text/door.
4. **Stretch** — open-ended VLM Q&A, more belt zones, richer safety language.

Protect rung 1 first: a depth-only belt that works beats five unstable models.

## Demo script (5 min)

See [demo_script.md](demo_script.md). Hook → airplane mode → blindfold obstacle run →
dashboard view → voice question → on-device privacy/latency pitch → fallback plan.

## Risk register

See [risk_register.md](risk_register.md). Top risks: motor arrival delay, BLE drops, Wi-Fi
isolation, NPU/QNN export difficulty, CV too slow, OCR instability, Sunday integration, and the
phone being collected overnight.

## Build philosophy

The winning version is the one that works reliably in front of judges. Protect this path:
`camera or mock SceneState → belt command → physical haptic response → clear on-device story`.
Everything else is additive.
