# Galaxy S25 Ultra + MacBook setup

We only have MacBooks; the target phone is a provided **Samsung Galaxy S25 Ultra**, connected
over **USB-C adb** as the primary path.

## 1. Install Android Studio

1. Download Android Studio: https://developer.android.com/studio
2. Open it and complete the setup wizard (installs the latest SDK + emulator bits).
3. Open **Settings → Languages & Frameworks → Android SDK** and confirm an SDK Platform
   (API 34/35) and **Android SDK Platform-Tools** are installed.

## 2. Install SDK Platform Tools / adb

Platform Tools ship with Android Studio. To also get `adb` on the command line, add it to your
PATH (Apple Silicon and Intel both use `~/Library/Android/sdk`):

```bash
echo 'export ANDROID_HOME="$HOME/Library/Android/sdk"' >> ~/.zshrc
echo 'export PATH="$ANDROID_HOME/platform-tools:$PATH"' >> ~/.zshrc
source ~/.zshrc
adb version
```

(Standalone option: `brew install android-platform-tools`.)

## 3. Enable Developer Options on the S25

1. **Settings → About phone → Software information.**
2. Tap **Build number** seven times until it says "Developer mode enabled."
3. Go back to **Settings → Developer options.**

## 4. Enable USB debugging

1. In **Developer options**, turn on **USB debugging**.
2. (Optional) Turn on **Stay awake** while charging for demo convenience.

## 5. Connect over USB-C and trust the Mac

1. Plug the phone into the MacBook with a **data-capable USB-C cable** (some cables are
   charge-only — if `adb devices` shows nothing, try another cable/port).
2. On the phone, choose a USB mode that allows data (e.g. **File Transfer / Android Auto**), not
   "charge only."
3. Tap **Allow** on the "Allow USB debugging?" dialog; check **Always allow from this computer**.

## 6. Confirm the device

```bash
adb devices -l
```

You should see one device listed as `device`. If it shows `unauthorized`, re-check the phone for
the allow dialog. If it shows `no permissions` or nothing:

```bash
adb kill-server && adb start-server && adb devices -l
```

## 7. Open the project in Android Studio

- Open the **`android/`** folder (not the repo root).
- Let Gradle sync; on first open Android Studio will **generate the Gradle wrapper** if missing.
- If prompted, install any missing SDK packages it requests.

## 8. Build + install the debug APK

In Android Studio: select the `app` config and the connected device, press **Run**. Or from the
command line once the wrapper exists:

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.sixthsense -c android.intent.category.LAUNCHER 1
```

(These are exactly what the MCP `gradle_build`, `install_debug_apk`, and `launch_app` tools do.)

## 9. Inspect logs

```bash
adb logcat -d -t 250 SixthSenseScene:I SixthSenseMCP:I AndroidRuntime:E '*:S'
```

Tags: `SixthSenseScene` (state updates), `SixthSenseMCP` (belt/debug/voice actions).

## 10. Optional: scrcpy screen mirroring

```bash
brew install scrcpy
scrcpy
```

Great for projecting the operator screen during the demo.

## 11. Run the dashboard from the Mac

```bash
cd dashboard
npm install
npm run dev -- --host 127.0.0.1
```

Open `http://127.0.0.1:5173`, enter the phone's IP, and connect to `ws://PHONE_IP:8080`.
If the socket fails it replays `src/mockFrames.json`.

## 12. Avoiding conference Wi-Fi isolation

Conference networks often isolate clients, blocking phone↔laptop traffic. Plan around it:

- **USB first.** All build/install/log/debug control runs over USB adb — no network needed.
- The **airplane-mode demo needs no network at all** once the app + models are on the phone.
- For the live dashboard (optional), use, in order: a **laptop hotspot**, **phone hotspot**, a
  **travel router**, or **USB tethering**. As a last resort, the dashboard runs entirely from
  `mockFrames.json` with no phone connection.

Find the phone IP: **Settings → Connections → Wi-Fi → (network) → IP address**, or
`adb shell ip addr show wlan0`.
