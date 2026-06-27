package com.sixthsense.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Drives the PHONE'S OWN single vibration motor with directional signatures, so a
 * user holding the phone near the waist feels which way an obstacle is (left /
 * center / right) with NO external hardware. This is the TEST MODE actuator — it
 * consumes the exact same belt packet `[L, C, R, pattern]` as the BLE belt
 * (`BeltMapper.packetAsInts`).
 *
 * Direction/intensity decisions live in the pure [DirectionalEncoding]; this class
 * only resolves the system [Vibrator] and turns a signature into a [VibrationEffect].
 *
 * Requires `<uses-permission android:name="android.permission.VIBRATE" />`.
 */
class PhoneHapticsActuator(context: Context) {

    private val vibrator: Vibrator = resolveVibrator(context.applicationContext)
    private val amplitudeControl: Boolean = runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(false)

    /** Last packet armed, so identical consecutive packets don't restart the loop each frame. */
    @Volatile
    private var lastKey: String? = null

    fun hasVibrator(): Boolean = runCatching { vibrator.hasVibrator() }.getOrDefault(false)

    /** Drive the motor from a belt packet `[L, C, R, pattern]`. */
    fun onBeltPacket(packet: List<Int>) {
        val l = packet.getOrElse(0) { 0 }
        val c = packet.getOrElse(1) { 0 }
        val r = packet.getOrElse(2) { 0 }
        val pattern = packet.getOrElse(3) { 0 }

        val key = "$l,$c,$r,$pattern"
        if (key == lastKey) return
        lastKey = key

        val sig = DirectionalEncoding.encode(l, c, r, pattern)
        if (sig == null) {
            stop()
            return
        }
        val effect =
            if (amplitudeControl) VibrationEffect.createWaveform(sig.timings, sig.amplitudes, sig.repeat)
            // No amplitude control: the timings already encode the same off/on rhythm
            // (amplitudes are 0 exactly on the off-segments). DirectionalEncoding always
            // emits repeat=0, so this two-arg overload reproduces the full looping signature.
            else VibrationEffect.createWaveform(sig.timings, sig.repeat)
        try {
            vibrator.cancel()           // "update" = cancel + re-arm (no live-amplitude API)
            vibrator.vibrate(effect)
            Log.i(TAG, "Phone haptics: ${sig.direction} amp<=${sig.amplitudes.maxOrNull()} packet=$key")
        } catch (e: Throwable) {
            Log.w(TAG, "vibrate() failed: ${e.message}")
        }
    }

    /** Stop any ongoing buzz (test mode off / path clear). */
    fun stop() {
        lastKey = null
        runCatching { vibrator.cancel() }
    }

    companion object {
        private const val TAG = "SixthSenseMCP"

        private fun resolveVibrator(ctx: Context): Vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) api31Vibrator(ctx)
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

        @RequiresApi(Build.VERSION_CODES.S)
        private fun api31Vibrator(ctx: Context): Vibrator {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            // getVibratorIds().size is 1 on the Galaxy S25 Ultra -> no spatial L/R;
            // the default vibrator is what the temporal encoding drives.
            return vm.defaultVibrator
        }
    }
}
