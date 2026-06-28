# SixthSense — Full Demo (this branch: `full-demo`)

Everything needed to run the on-device navigation copilot end-to-end: the Galaxy
S25 Ultra runs all perception locally, and a laptop dashboard mirrors the live
camera + detections so judges can see what the harnessed phone sees.

## TL;DR

```bash
scripts/demo.sh          # build → install on the S25 → launch the live dashboard
```

Then open **http://localhost:5173** and tap **Start vision** on the phone. Done.

No phone handy? Preview the whole dashboard from a synthetic feed:

```bash
scripts/demo.sh --fake
```

## What `scripts/demo.sh` does

1. Checks the on-device models are present (see step 0 below).
2. Builds `app-debug.apk` (Gradle).
3. Installs it on the S25 over USB (clean reinstall if the signing key differs).
4. Grants the camera permission and keeps the screen awake while plugged in.
5. Launches the app, then serves the dashboard and wires the phone link
   (`adb forward tcp:8080`), opening your browser.

Flags: `--fake` (no phone), `--no-build` (reuse last APK), `--no-open`.

## Prerequisites (one-time, per machine)

- macOS with **JDK 21**, the **Android SDK** + `adb` (`~/Library/Android/sdk`),
  **Node** and **Python 3**.
- `android/local.properties` with `sdk.dir=...` (already present here).
- The S25 with **USB debugging** enabled.

### Step 0 — the model files (NOT in git)

The `.pte` model binaries are git-ignored on purpose (they're ~500 MB and never
belong in the repo). A fresh clone must have them dropped in once:

```
android/app/src/main/assets/models/
  depth.pte          # required — Depth-Anything-V2
  yolo.pte           # required — YOLOv11n
  qwen.pte           # optional — voice-agent LLM
  qwen-tokenizer/    # optional — tokenizer for qwen.pte
```

Get them from the team's shared models (AirDrop / drive) or re-export them via
`~/sixthsense-models`. On the team's dev machine they're already in place.

## Run the demo

1. Plug the S25 into the laptop (USB-C). Approve "Allow USB debugging" on the phone.
2. `scripts/demo.sh`
3. Open **http://localhost:5173** — the top flips to **LIVE**.
4. On the phone, tap **Start vision** (grant camera if asked).
5. The live camera + detection boxes appear. Boxes turn **red** when an object is
   close (the same threshold that buzzes the belt).

### Connection options

- **USB (default, used by `demo.sh`):** `adb forward` tunnels the phone's
  WebSocket to `ws://localhost:8080`. Works in airplane mode. For a walking demo,
  one person wears the harness and a buddy carries the laptop on a long USB-C cable.
- **Wi-Fi / hotspot (cable-free roaming):** set the dashboard's **Device** box to
  `ws://<phone-ip>:8080`. Many shared/conference Wi-Fi networks block
  device-to-device traffic ("client isolation"); if the dashboard can't connect,
  turn on the **phone's hotspot** and join it from the laptop (no isolation there).

## Optional — the 4-motor haptic belt

The belt is a separate ESP32 device (firmware in `firmware/esp32_belt/`). Flash it
per `firmware/esp32_belt/README.md` (Arduino IDE + NimBLE), power it, and the app
auto-connects over BLE and drives it from the live scene. The demo works without
the belt — the phone shows the same state on screen and in the dashboard.

## Troubleshooting

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — a build with a different signing key
  is installed. `demo.sh` handles this automatically (uninstall + clean install).
- **Dashboard stuck "CONNECTING"** — the app must be running and you must tap
  **Start vision**; the phone only streams while a dashboard is connected.
- **Feed freezes when the phone screen sleeps** — `demo.sh` sets
  `stay_on_while_plugged_in`; keep the cable connected.
- **Wi-Fi won't connect but USB does** — client isolation; use the phone hotspot.
