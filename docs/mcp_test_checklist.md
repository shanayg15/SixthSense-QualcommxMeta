# MCP test checklist

Exact prompts to verify the `sixthsense` MCP server end-to-end in Claude Code. Run them in
order. The belt/mock/voice steps require the **debug** APK installed and running, and (for the
belt) a connected ESP32 belt.

> Safety: Claude will not install the APK, send belt commands, toggle mock mode, ask the voice
> agent, or change device settings unless you explicitly ask — these prompts are that explicit
> request.

## Prerequisites

- `adb devices -l` shows the S25 as `device` (see [galaxy_s25_mac_setup.md](galaxy_s25_mac_setup.md)).
- MCP registered: `claude mcp list` shows `sixthsense`, and `/mcp` lists its tools.

## Prompts

1. **List devices**
   > Use the sixthsense MCP server to list adb devices.

   Expect: `adb_devices` output with the S25 as `device`.

2. **Build the debug APK**
   > Use MCP to build the debug APK.

   Expect: `gradle_build` runs `assembleDebug` from `android/`. If there is no Gradle wrapper
   yet, the tool tells you to open `android/` in Android Studio once to generate it.

3. **Install and launch**
   > Use MCP to install and launch the app.

   Expect: `install_debug_apk` then `launch_app` (`com.sixthsense`) → app opens on the phone.

4. **Mock mode on + read logs**
   > Use MCP to turn mock mode on and read logs.

   Expect: `set_mock_mode(true)` broadcast, then `adb_logcat` shows `SixthSenseScene` updates
   cycling through the scripted scenes.

5. **Right belt test at intensity 200**
   > Use MCP to send a right belt test at intensity 200.

   Expect: `belt_test(left=0, center=0, right=200, pattern=0)` → right motor buzzes (belt must
   be connected; otherwise the log shows "belt not connected").

6. **Ask the voice agent**
   > Use MCP to ask the voice agent what's ahead of me.

   Expect: `ask_voice_agent("what's ahead of me?")` → logcat shows the rule-based answer from
   the current SceneState (`SixthSenseMCP`).

7. **Dashboard status**
   > Use MCP to check dashboard status.

   Expect: `dashboard_status` reports responding/not. Start it first with
   `cd dashboard && npm run dev` (or ask MCP for the command via `start_dashboard`).

## Bonus

> Use MCP to clear logcat, then turn mock mode off.

> Use MCP to check Qualcomm AI Hub status.

If a step fails, see Troubleshooting in [../mcp/README.md](../mcp/README.md).
