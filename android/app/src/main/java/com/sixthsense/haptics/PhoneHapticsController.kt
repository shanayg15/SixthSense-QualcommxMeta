package com.sixthsense.haptics

import android.util.Log
import com.sixthsense.core.BeltMapper
import com.sixthsense.core.SceneBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * TEST MODE controller: when enabled, it mirrors the live [SceneBus] to the phone's
 * own vibration motor through [PhoneHapticsActuator], so the demo can feel
 * directional obstacle feedback with NO belt and NO BLE — just hold the phone near
 * the waist. It derives the packet the same way the BLE belt does
 * ([BeltMapper.packetAsInts]) so test mode and the real belt share one source of truth.
 */
class PhoneHapticsController(
    private val bus: SceneBus,
    private val actuator: PhoneHapticsActuator,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var enabled = false
    private var job: Job? = null

    fun isEnabled(): Boolean = enabled

    fun hasVibrator(): Boolean = actuator.hasVibrator()

    /**
     * Fire a single belt packet at the phone motor directly, independent of the
     * test-mode subscription. Lets the debug belt broadcast (adb) produce a
     * directional buzz with no camera and no BLE belt connected.
     */
    fun driveOnce(packet: List<Int>) = actuator.onBeltPacket(packet)

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        Log.i(TAG, "Phone haptics test mode -> $enabled (hasVibrator=${actuator.hasVibrator()})")
        if (enabled) start() else stop()
    }

    private fun start() {
        job?.cancel()
        job = scope.launch {
            // collectLatest: if scenes arrive faster than we re-arm, only the newest matters.
            bus.state.collectLatest { scene ->
                actuator.onBeltPacket(BeltMapper.packetAsInts(scene))
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        actuator.stop()
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
    }
}
