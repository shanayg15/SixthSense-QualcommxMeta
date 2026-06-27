# Android debug receiver — reference snippet

This is the **debug-only** bridge between adb / the MCP server and the running app. It ships
**only in the `debug` build variant** and is never part of the assistive runtime. The live
files are:

- `android/app/src/debug/AndroidManifest.xml`
- `android/app/src/debug/java/com/sixthsense/debug/DebugReceiver.kt`

## Debug broadcast actions

| Action | Extras | Effect |
|---|---|---|
| `com.sixthsense.DEBUG_BELT` | `l`,`c`,`r` int 0–255; `p` int 0–2 | Send a belt packet |
| `com.sixthsense.DEBUG_MOCK` | `enabled` bool | Toggle mock scene mode |
| `com.sixthsense.DEBUG_ASK` | `q` string | Ask the voice agent |

## Manifest (src/debug/AndroidManifest.xml)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <receiver
            android:name="com.sixthsense.debug.DebugReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.sixthsense.DEBUG_BELT" />
                <action android:name="com.sixthsense.DEBUG_MOCK" />
                <action android:name="com.sixthsense.DEBUG_ASK" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

## Receiver (src/debug/java/com/sixthsense/debug/DebugReceiver.kt)

```kotlin
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context) // components may not exist yet
        when (intent.action) {
            "com.sixthsense.DEBUG_BELT" -> {
                val l = intent.getIntExtra("l", 0).coerceIn(0, 255)
                val c = intent.getIntExtra("c", 0).coerceIn(0, 255)
                val r = intent.getIntExtra("r", 0).coerceIn(0, 255)
                val p = intent.getIntExtra("p", 0).coerceIn(0, 2)
                AppGraph.beltClient.send(
                    byteArrayOf(l.toByte(), c.toByte(), r.toByte(), p.toByte()))
                Log.i("SixthSenseMCP", "DEBUG_BELT l=$l c=$c r=$r p=$p")
            }
            "com.sixthsense.DEBUG_MOCK" ->
                AppGraph.mockSceneProducer.setEnabled(intent.getBooleanExtra("enabled", true))
            "com.sixthsense.DEBUG_ASK" ->
                AppGraph.voiceAgent.ask(intent.getStringExtra("q") ?: "what's ahead of me?")
        }
    }
}
```

## Manual adb equivalents (DEV-ONLY)

```bash
# Belt: strong right buzz, steady
adb shell am broadcast -a com.sixthsense.DEBUG_BELT --ei l 0 --ei c 0 --ei r 200 --ei p 0

# Mock mode on / off
adb shell am broadcast -a com.sixthsense.DEBUG_MOCK --ez enabled true
adb shell am broadcast -a com.sixthsense.DEBUG_MOCK --ez enabled false

# Voice agent
adb shell am broadcast -a com.sixthsense.DEBUG_ASK --es q "what's ahead of me?"
```

These mirror the MCP `belt_test`, `set_mock_mode`, and `ask_voice_agent` tools, and the
`ss_belt` / `ss_mock` / `ss_ask` helpers in `scripts/adb_common.sh`.
