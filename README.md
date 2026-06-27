# SixthSense

**An on-device navigation copilot for blind and low-vision users.**

A chest-mounted Samsung Galaxy S25 Ultra watches the path ahead, runs vision + reasoning
models locally (ExecuTorch / Qualcomm QNN where possible), and converts the scene into a
compact `SceneState`. That state drives three outputs:

1. **Haptic belt** — a BLE ESP32 belt with left/center/right vibration motors (a *dumb actuator*).
2. **On-device voice agent** — push-to-talk answers to "what's ahead?", "read that sign", "find the exit".
3. **Live dashboard** — a React/Vite visualization for judges (visualization only, no AI).

The thesis: **navigation safety should not depend on cloud connectivity.** The demo runs
fully on-device and works in **airplane mode** once the app and models are on the phone.

> **Claude / MCP is a development & debugging command center only.** It is used to build,
> install, inspect logs, drive debug broadcasts, and manage the repo. It is **never** part of
> the live assistive runtime. See [CLAUDE.md](CLAUDE.md).

---

## Repo structure

```text
sixthsense/
  README.md              This file
  CLAUDE.md              Rules for Claude/MCP (dev-only) + architecture + MVP ladder
  .gitignore

  docs/                  Project context, setup, demo, risk, model export, MCP checklist
  mcp/                   Custom Python FastMCP server (dev/debug command center)
  scripts/               setup_mcp.sh, verify_android_env.sh, adb_common.sh
  android/               Android Studio project (Kotlin, package com.sixthsense)
  dashboard/             React + Vite + TypeScript visualization
  firmware/esp32_belt/   ESP32 NimBLE belt firmware (dumb actuator)
```

## What is real vs mock in this starter repo

| Component | Status in starter |
|---|---|
| `SceneState` contract | **Real** — final data shape |
| `MockSceneProducer` | **Real** — scripted scene sequence at ~5–8 Hz |
| `BeltMapper` | **Real** — SceneState → 4-byte packet |
| `BeltClient` (BLE) | **Realistic skeleton** — Nordic UART UUIDs, GATT write |
| Debug broadcast receiver | **Real** — drives belt/mock/voice for MCP |
| `VisionPipeline` | **Mock/placeholder** — TODOs for CameraX + ExecuTorch + QNN |
| `VoiceAgent` | **Placeholder** — rule-based answers from SceneState; TODOs for Whisper/Llama/TTS |
| `SceneSocket` (WebSocket :8080) | **Skeleton** — broadcasts SceneState JSON; falls back gracefully |
| Dashboard | **Real** — live WebSocket with mock-frame replay fallback |
| ESP32 firmware | **Real starter** — NimBLE, 3 PWM motors, steady/single/double patterns |
| `.pte` model files | **Not included** — see [docs/model_export_plan.md](docs/model_export_plan.md) |

---

## MacBook setup order

We only have MacBooks. The target phone is a provided **Samsung Galaxy S25 Ultra**, connected
over **USB-C adb** as the primary path.

1. **Install prerequisites** — Android Studio (+ SDK Platform Tools), Node 18+, Python 3.10+,
   and ideally [`uv`](https://docs.astral.sh/uv/). See [docs/galaxy_s25_mac_setup.md](docs/galaxy_s25_mac_setup.md).
2. **Verify the environment**:
   ```bash
   bash scripts/verify_android_env.sh
   ```
3. **Set `ANDROID_HOME`** (add to `~/.zshrc`):
   ```bash
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   export PATH="$ANDROID_HOME/platform-tools:$PATH"
   ```

## Open the Android project in Android Studio

Open the **`android/`** folder (not the repo root) in Android Studio. On first open, Android
Studio will sync Gradle and **generate the Gradle wrapper** if it is missing. Let the sync
finish, then build the debug variant.

## Connect the Galaxy S25 over USB adb

1. On the phone: **Settings → About phone → Software information →** tap **Build number** 7×
   to enable Developer options.
2. **Settings → Developer options → USB debugging → ON.**
3. Plug the phone into the MacBook with a **data-capable USB-C cable**.
4. On the phone, tap **Allow** on the "Allow USB debugging?" dialog (check "Always allow").
5. Confirm:
   ```bash
   adb devices -l
   ```
   The phone should appear as `device` (not `unauthorized`).

Full detail (including conference-Wi-Fi workarounds) is in
[docs/galaxy_s25_mac_setup.md](docs/galaxy_s25_mac_setup.md).

## Run the MCP setup (dev command center)

```bash
bash scripts/setup_mcp.sh
```

This verifies `claude`, `adb`, and `uv`, prepares the MCP environment, and registers the
`sixthsense` MCP server with Claude Code at **project scope**. Manual registration:

```bash
claude mcp add --transport stdio --scope project sixthsense -- uv --directory "$(pwd)/mcp" run sixthsense_mcp.py
```

See [mcp/README.md](mcp/README.md) and [docs/mcp_test_checklist.md](docs/mcp_test_checklist.md).

## Run the dashboard

```bash
cd dashboard
npm install
npm run dev -- --host 127.0.0.1
```

Open the printed URL (default `http://127.0.0.1:5173`). Set the phone IP in the UI to connect
to `ws://PHONE_IP:8080`; if the socket fails it replays `src/mockFrames.json`.

## Flash the ESP32 belt

See [firmware/esp32_belt/README.md](firmware/esp32_belt/README.md). In short: Arduino IDE +
ESP32 board package + NimBLE-Arduino, wire 3 vibration motors through a **ULN2803A** driver
(never straight off GPIO), flash `esp32_belt.ino`, and test packets from nRF Connect.

---

## Safety & demo rules

- The phone performs **all** perception and reasoning locally.
- The belt is a **dumb actuator**; the dashboard is **visualization only**.
- The final demo must still work in **airplane mode** once the app/models are on device.
- **No cloud APIs, no external LLM calls, no Claude** in the live assistive runtime.
- Debug broadcast tools are **debug-only** and live in the `debug` build variant.
