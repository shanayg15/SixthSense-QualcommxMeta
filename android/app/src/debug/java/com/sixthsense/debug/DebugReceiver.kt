package com.sixthsense.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DEBUG-ONLY receiver. Bridges adb / the SixthSense MCP server to the running
 * app for development and demo control. Ships only in the `debug` build variant
 * (registered in src/debug/AndroidManifest.xml). NEVER part of the assistive
 * runtime.
 *
 * Actions:
 *   com.sixthsense.DEBUG_BELT     extras l,c,r (int 0-255), p (int 0-3)
 *   com.sixthsense.DEBUG_MOCK     extra  enabled (bool)
 *   com.sixthsense.DEBUG_ASK      extra  q (string)
 *   com.sixthsense.DEBUG_HAPTICS  extra  enabled (bool) — phone-vibration test mode
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Ensure components exist even if the broadcast arrives before the UI.
        AppGraph.init(context)

        when (intent.action) {
            ACTION_BELT -> {
                val l = clamp(intent.getIntExtra("l", 0), 0, 255)
                val c = clamp(intent.getIntExtra("c", 0), 0, 255)
                val r = clamp(intent.getIntExtra("r", 0), 0, 255)
                val p = clamp(intent.getIntExtra("p", 0), 0, 3)
                val packet = byteArrayOf(l.toByte(), c.toByte(), r.toByte(), p.toByte())
                Log.i(TAG, "DEBUG_BELT l=$l c=$c r=$r p=$p")
                AppGraph.beltClient.send(packet)
                // Also fire the phone's own motor so directional buzz is testable
                // over adb with no camera and no BLE belt connected.
                AppGraph.phoneHaptics.driveOnce(listOf(l, c, r, p))
            }

            ACTION_MOCK -> {
                val enabled = intent.getBooleanExtra("enabled", true)
                Log.i(TAG, "DEBUG_MOCK enabled=$enabled")
                AppGraph.mockSceneProducer.setEnabled(enabled)
            }

            ACTION_HAPTICS -> {
                val enabled = intent.getBooleanExtra("enabled", true)
                Log.i(TAG, "DEBUG_HAPTICS enabled=$enabled")
                AppGraph.phoneHaptics.setEnabled(enabled)
            }

            ACTION_ASK -> {
                val q = intent.getStringExtra("q") ?: "what's ahead of me?"
                Log.i(TAG, "DEBUG_ASK q=\"$q\"")
                // Route through askAsync so the on-device Qwen LLM answers (off-thread);
                // falls back to the rule-based answer if the LLM isn't ready.
                AppGraph.voiceAgent.askAsync(q) { answer ->
                    Log.i(TAG, "DEBUG_ASK answer=\"$answer\"")
                }
            }

            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = v.coerceIn(lo, hi)

    companion object {
        private const val TAG = "SixthSenseMCP"
        private const val ACTION_BELT = "com.sixthsense.DEBUG_BELT"
        private const val ACTION_MOCK = "com.sixthsense.DEBUG_MOCK"
        private const val ACTION_ASK = "com.sixthsense.DEBUG_ASK"
        private const val ACTION_HAPTICS = "com.sixthsense.DEBUG_HAPTICS"
    }
}
