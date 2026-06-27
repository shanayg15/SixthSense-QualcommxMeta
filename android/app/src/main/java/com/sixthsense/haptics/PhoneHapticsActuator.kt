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

    /**
     * Coarse signature of what's currently playing: direction + an amplitude bucket.
     * We re-arm ONLY when this changes — NOT on every raw L/C/R jitter. Keying on the
     * raw packet (the old bug) re-armed the looping waveform every camera frame, so its
     * directional rhythm never played and all directions felt identical. Keying on the
     * direction lets a persistent direction's pulse pattern actually loop and be felt.
     */
    @Volatile
    private var lastKey: String? = null

    fun hasVibrator(): Boolean = runCatching { vibrator.hasVibrator() }.getOrDefault(false)

    /** Drive the motor from a belt packet `[L, C, R, pattern]`. */
    fun onBeltPacket(packet: List<Int>) {
        val sig = DirectionalEncoding.encode(
            packet.getOrElse(0) { 0 }, packet.getOrElse(1) { 0 },
            packet.getOrElse(2) { 0 }, packet.getOrElse(3) { 0 },
        )
        if (sig == null) {
            stop()
            return
        }
        // Re-arm only on a meaningful change (direction, or a coarse intensity bucket).
        val maxAmp = sig.amplitudes.maxOrNull() ?: 0
        val key = "${sig.direction}|${maxAmp / AMP_BUCKET}"
        if (key == lastKey) return
        lastKey = key

        val effect =
            if (amplitudeControl) VibrationEffect.createWaveform(sig.timings, sig.amplitudes, sig.repeat)
            // No amplitude control: the timings already encode the same off/on rhythm
            // (amplitudes are 0 exactly on the off-segments); repeat is always 0.
            else VibrationEffect.createWaveform(sig.timings, sig.repeat)
        try {
            vibrator.cancel()           // re-arm: cancel + re-vibrate (no live-amplitude API)
            vibrator.vibrate(effect)
            Log.i(TAG, "Phone haptics: ${sig.direction} amp<=$maxAmp key=$key")
        } catch (e: Throwable) {
            Log.w(TAG, "vibrate() failed: ${e.message}")
        }
    }

    /** Stop any ongoing buzz (test mode off / path clear). Idempotent. */
    fun stop() {
        if (lastKey == null) return
        lastKey = null
        runCatching { vibrator.cancel() }
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
        /** Amplitude bucket width: intensity must change by this much to re-arm (avoids jitter churn). */
        private const val AMP_BUCKET = 85

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
