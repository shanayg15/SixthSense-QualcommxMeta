package com.sixthsense.ble

import android.util.Log
import com.sixthsense.core.BeltMapper
import com.sixthsense.core.SceneBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Drives the **4-motor ESP32 belt** from the live [SceneBus] over BLE — the
 * "shift the haptics from the phone to the motors" path.
 *
 * Mapping (see [BeltMapper.beltPacket4]): the obstacle's side is what buzzes —
 * LEFT motor for a left obstacle, RIGHT motor for a right one, and BOTH center
 * motors (front of the waist) for something straight ahead. Silent when clear.
 *
 * Sends a packet only when it CHANGES; the firmware holds the last command and
 * runs the pattern envelope, so the belt keeps buzzing without BLE spam. Auto
 * starts/stops with the BLE connection (wired in AppGraph).
 */
class BeltController(
    private val bus: SceneBus,
    private val belt: BeltClient,
    private val scope: CoroutineScope,
) {
    @Volatile private var enabled = false
    private var job: Job? = null
    private var lastSent: ByteArray? = null

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        Log.i(TAG, "Belt drive -> $enabled (connected=${belt.isConnected})")
        if (enabled) start() else stop()
    }

    private fun start() {
        job?.cancel()
        lastSent = null
        job = scope.launch {
            bus.state.collect { scene ->
                if (!belt.isConnected) return@collect
                val packet = BeltMapper.beltPacket4(scene)
                if (packet.contentEquals(lastSent)) return@collect  // send only on change
                lastSent = packet
                belt.send(packet)
            }
        }
    }

    private fun stop() {
        job?.cancel(); job = null
        lastSent = null
        runCatching { if (belt.isConnected) belt.send(STOP) }  // all motors off
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
        private val STOP = byteArrayOf(0, 0, 0, 0, 0)  // 4 motors + pattern
    }
}
