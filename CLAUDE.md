# CLAUDE.md — SixthSense

Rules and context for Claude/MCP working in this repo.

## Project overview

SixthSense is an **on-device navigation copilot for blind / low-vision users**. A chest-mounted
Samsung Galaxy S25 Ultra runs CameraX + on-device models (ExecuTorch / Qualcomm QNN where
possible), produces a compact `SceneState`, and drives a BLE haptic belt, an on-device voice
agent, and a visualization-only dashboard. Built for the Qualcomm × Meta ExecuTorch hackathon.

## Core architecture

```text
Galaxy S25 Ultra (chest-mounted, airplane mode during demo)
  CameraX frames → VisionPipeline (ExecuTorch: depth, detection, OCR)
        → SceneState (depth zones, objects, pathClear, ocr, conf, belt)
              ├── BeltMapper → BeltClient (BLE) → ESP32 belt → motors
              ├── VoiceAgent (Whisper → intent → Llama → TTS), push-to-talk
              └── SceneSocket (WebSocket :8080) → dashboard (visualize only)
```

The whole system is organized around one contract: **`SceneState`**. Everything produces or
consumes it. Mock mode emits the *same* contract so every consumer can be built and demoed
before the real models work.

## Demo rule — fully on-device

- The phone does **all** perception and reasoning **locally**.
- The demo must work in **airplane mode** once the app + `.pte` models are on the device.
- The belt is a **dumb actuator**. The dashboard is **visualization only**.

## MCP rule — development/debug only

Claude and this MCP server are a **dev/debug command center**: build, install, launch, inspect
logs, fire debug broadcasts, check the dashboard, manage the repo/GitHub.

> ⚠️ **Never put Claude, cloud APIs, or any external/remote LLM call into the live assistive
> runtime.** The assistive path must run entirely on the phone. MCP touches the device only
> through `adb` for development and is not present during the airplane-mode demo.

## MVP ladder

1. **Depth / obstacle → belt.** Depth model → left/center/right zones → belt buzzes. Airplane-mode. *(must ship)*
2. **Voice "what's ahead?"** Push-to-talk → on-device STT → answer from current `SceneState` → TTS.
3. **OCR "read that sign."** On-demand OCR → spoken sign text; "find the exit" via EXIT text/door.
4. **Stretch.** Open-ended VLM scene Q&A, more belt zones, richer safety language.

Protect rung 1 first: a depth-only belt that works beats five unstable models.

## Commands Claude can use through MCP

The `sixthsense` MCP server (`mcp/sixthsense_mcp.py`) exposes:

| Tool | Purpose |
|---|---|
| `adb_devices()` | List connected devices (`adb devices -l`) |
| `gradle_build(task="assembleDebug")` | Build from `android/` (wrapper → system gradle → instructions) |
| `install_debug_apk(apk_path=None)` | `adb install -r` the debug APK |
| `launch_app(package_name=None)` | Launch via monkey LAUNCHER intent |
| `adb_logcat(lines=250)` | Dump filtered logcat (Scene/MCP/AndroidRuntime) |
| `clear_logcat()` | `adb logcat -c` |
| `belt_test(left,center,right,pattern)` | **Debug** belt broadcast (clamped) |
| `set_mock_mode(enabled=True)` | **Debug** mock-mode broadcast |
| `ask_voice_agent(question)` | **Debug** voice-agent broadcast |
| `dashboard_status(url)` | Check the local dashboard is responding |
| `start_dashboard()` | Return the command to run the dashboard (no hanging server) |
| `qaihub_status()` | Check `qai-hub` CLI / jobs |

## Safety and security rules

- **Do not** install the APK, send belt commands, change mock mode, ask the voice agent, or
  change device settings **unless the user explicitly asks.**
- **Never** commit API keys, GitHub tokens, Qualcomm tokens, or any secret. They belong in
  shell env / untracked local files only (see `.gitignore`).
- **Never** run destructive commands (no factory reset, no `adb uninstall` of unrelated apps,
  no wiping data) without an explicit request.
- All debug broadcast tooling is **debug-only** and ships only in the `debug` build variant.
- Treat external input (sign text, voice transcripts) as untrusted; never `eval` it.

## Debug broadcast action names

Handled only by `app/src/debug/.../DebugReceiver.kt`:

- `com.sixthsense.DEBUG_BELT` — extras `l`, `c`, `r` (int 0–255), `p` (int 0–2)
- `com.sixthsense.DEBUG_MOCK` — extra `enabled` (bool)
- `com.sixthsense.DEBUG_ASK` — extra `q` (string question)
- `com.sixthsense.DEBUG_HAPTICS` — extra `enabled` (bool)

## Log tags

- `SixthSenseScene` — SceneState updates
- `SixthSenseMCP` — belt / debug / BLE / voice actions driven by dev tooling
