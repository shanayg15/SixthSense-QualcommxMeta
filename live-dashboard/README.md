# SixthSense — Live Vision Dashboard

A **deployable, framework-free** dashboard that shows the **Galaxy S25 Ultra
camera feed and the on-device technologies working on it in real time**. It is a
pure client: it connects to the phone over WebSocket and renders exactly what the
device sends. **No synthetic scenes, no webcam stand-in, no mock data** — when
there's no device, it says so.

- **Visualization only.** No AI runs here.
- **Plain HTML / CSS / JS.** No React, no build step, no dependencies, no web
  fonts. Salesforce Lightning theme (white/blue), square corners, flat fills.

## Deploy

It's a static site — host it anywhere or run it locally:

```bash
cd live-dashboard
python3 -m http.server 5173      # → http://127.0.0.1:5173
# or: any static host (Netlify / Vercel / GitHub Pages / nginx)
```

Set the **Device** address (top-left) to the phone's WebSocket, e.g.
`ws://192.168.1.50:8080`, and press **Connect**. The address is saved and the
dashboard **auto-connects and auto-reconnects** if the link drops.

> **HTTP vs HTTPS:** browsers block `ws://` from an `https://` page. For the LAN
> demo, serve the dashboard over `http://` (or `file://`) and use a `ws://`
> device URL. If you host it on HTTPS, the phone must serve `wss://` (TLS).

## What it shows

- **Camera — Galaxy S25 Ultra** — the live device frame with perception overlays
  drawn on top (toggle each layer): YOLO detection boxes, per-zone depth tint,
  OCR text, path-guidance banner, targeting HUD.
- **On-device technology** — Depth-Anything-V2, YOLOv11n/v8n, TrOCR, Whisper,
  Llama 3.2 1B, Android TextToSpeech, the ExecuTorch runtime (Snapdragon SM8750,
  Hexagon v79 NPU, QNN backend / XNNPACK fallback), and the BLE haptic belt —
  each ACTIVE/STANDBY and its current output, driven by the **live SceneState**.
- Live readout, depth sensing, object detection, haptic output, voice agent
  (the device's interactions), pipeline, and raw `SceneState`. Every panel
  collapses; **Collapse all / Expand all** in the toolbar.

## Device protocol (what the phone sends)

The dashboard reads the broadcast `SceneState` plus two optional fields the
Android app now sends:

```json
{ "ts": 0,
  "depth": { "left": 0.42, "center": 0.71, "right": 0.30, "curbAhead": true, "stepDown": false },
  "objects": [ { "label": "chair", "zone": "left", "nearness": 0.38, "conf": 0.78 } ],
  "pathClear": false, "ocr": { "present": false, "text": "" }, "conf": 0.88, "belt": [0,0,200,0],
  "frame": "<base64 JPEG of the S25 camera frame>",
  "frameRotation": 90,
  "voice": { "question": "read that sign", "intent": "OCR", "answer": "The sign says: EXIT." } }
```

- `frame` — base64 JPEG (or a `data:` URL) of the current camera frame.
- `frameRotation` — degrees (0/90/180/270) the dashboard rotates the frame, so
  the phone sends the JPEG unrotated (no extra encode pass on-device).
- `voice` — the agent's last interaction (optional).

## Phone side (wired in the Android app)

The on-device `VisionPipeline` owns the camera, runs the ExecuTorch models, and
streams the live frame + voice to this dashboard — one camera, no second binding:

- `vision/VisionPipeline.kt` — its CameraX analyzer emits a downscaled JPEG
  (~8 fps, only while a dashboard client is connected) plus the rotation.
- `ws/SceneSocket.kt` — merges the latest `frame` / `frameRotation` / `voice`
  into each broadcast (the core `SceneState` contract is unchanged).
- `MainActivity.kt` — "Start vision" requests `CAMERA` and starts the pipeline,
  and wires the frame/voice sinks to the dashboard socket.

So the end-to-end path is real: **S25 camera → VisionPipeline (ExecuTorch) →
SceneSocket → this dashboard.** The belt-mapper fallback in `app.js` matches
`BeltMapper.kt` byte-for-byte (Kotlin float32 replicated), only used if the device
omits `belt`.

> The original React `../dashboard/` is left intact; this folder is the
> no-framework, deployable build.
