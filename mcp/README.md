# SixthSense MCP server

A local **stdio** MCP server (FastMCP) that acts as a **development & debugging command
center** for the SixthSense project. It builds the app, installs/launches it on the Galaxy
S25 over USB adb, inspects logs, fires *debug-only* broadcasts, checks the dashboard, and
reports Qualcomm AI Hub status.

> ⚠️ **Dev/debug only.** This server is never part of the live assistive runtime. It touches
> the phone only through `adb` during development. The airplane-mode demo does not use it.

## Tools

| Tool | What it does |
|---|---|
| `adb_devices()` | `adb devices -l` |
| `gradle_build(task="assembleDebug")` | Build from `android/` (gradlew → gradle → instructions) |
| `install_debug_apk(apk_path=None)` | `adb install -r` (auto-finds the debug APK) |
| `launch_app(package_name=None)` | Launch via LAUNCHER intent |
| `adb_logcat(lines=250)` | Filtered logcat dump |
| `clear_logcat()` | `adb logcat -c` |
| `belt_test(left,center,right,pattern)` | **Debug** belt broadcast (clamped 0–255 / 0–2) |
| `set_mock_mode(enabled=True)` | **Debug** mock-mode broadcast |
| `ask_voice_agent(question)` | **Debug** voice-agent broadcast |
| `dashboard_status(url=None)` | HTTP check of the dashboard |
| `start_dashboard()` | Returns the command to run (no hanging server) |
| `qaihub_status()` | `qai-hub` availability + jobs |

## Environment variables (all optional)

| Var | Default |
|---|---|
| `SIXTHSENSE_ROOT` | parent of the `mcp/` directory |
| `ANDROID_HOME` | `~/Library/Android/sdk` is also probed for `adb` |
| `SIXTHSENSE_PACKAGE` | `com.sixthsense` |
| `DASHBOARD_URL` | `http://127.0.0.1:5173` |

## Install `uv` (recommended)

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
# then restart your shell, or: source ~/.zshrc
```

## Run manually

With `uv` (resolves deps on first run):

```bash
uv --directory "$(pwd)/mcp" run sixthsense_mcp.py
```

Fallback without `uv` (venv + pip):

```bash
cd mcp
python3 -m venv .venv
source .venv/bin/activate
pip install "mcp[cli]" httpx
python sixthsense_mcp.py
```

It will appear to "hang" — that is correct; it is waiting for an MCP client on stdio. Ctrl-C
to stop. Diagnostics print to **stderr** (stdout is the protocol channel).

## Register with Claude Code

From the **repo root**:

```bash
claude mcp add --transport stdio --scope project sixthsense -- uv --directory "$(pwd)/mcp" run sixthsense_mcp.py
```

Verify:

```bash
claude mcp list
```

Inside Claude Code:

```text
/mcp
```

If you are not using `uv`, register the venv interpreter instead:

```bash
claude mcp add --transport stdio --scope project sixthsense -- "$(pwd)/mcp/.venv/bin/python" "$(pwd)/mcp/sixthsense_mcp.py"
```

## Test each tool

See [../docs/mcp_test_checklist.md](../docs/mcp_test_checklist.md) for exact prompts.

## Troubleshooting

- **`adb` unauthorized** — Look at the phone and accept the "Allow USB debugging?" prompt
  (check "Always allow from this computer"). Re-run `adb_devices`. If stuck:
  `adb kill-server && adb start-server`.
- **adb not found** — Install Android SDK Platform Tools and set
  `export ANDROID_HOME="$HOME/Library/Android/sdk"`. The server also probes that default path.
- **No APK found** — Run `gradle_build` first, or open `android/` in Android Studio to sync and
  generate the Gradle wrapper, then build the `debug` variant.
- **Dashboard not found / not responding** — `cd dashboard && npm install && npm run dev`. Pass
  the right `url` to `dashboard_status` if you changed the port.
- **Qualcomm AI Hub not configured** — `pip install qai-hub` and configure your token per
  [../docs/model_export_plan.md](../docs/model_export_plan.md). Never commit the token.
- **Server won't start** — Ensure Python ≥ 3.10 and that `mcp[cli]` installed. With `uv`, try
  `uv --directory mcp run --refresh sixthsense_mcp.py`.
