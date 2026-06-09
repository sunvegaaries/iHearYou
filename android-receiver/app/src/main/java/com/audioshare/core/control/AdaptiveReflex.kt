// android-receiver/app/src/main/java/com/audioshare/core/control/AdaptiveReflex.kt
package com.audioshare.core.control

import com.audioshare.core.buffer.JitterBuffer
import com.audioshare.core.audio.AudioPipeline
import kotlinx.coroutines.*

/**
 * Fast control loop: polls buffer health every ~500ms.
 * Changes target buffer depth. Hysteresis prevents oscillation.
 */
class AdaptiveReflex(
    private val jitterBuffer: JitterBuffer,
    private val pipeline: AudioPipeline
) {
    // Buffer target levels in ms
    private val levels = intArrayOf(120, 200, 300)
    private var levelIdx = 1   // start at 200ms

    private var lastChangeMs = 0L
    private val hysteresisMs = 1500L   // min time between changes

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(500)
                tick()
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun tick() {
        val now    = System.currentTimeMillis()
        val fillMs = jitterBuffer.getFillMs()

        if (now - lastChangeMs < hysteresisMs) return

        val lowThreshold  = levels[levelIdx] * 0.35
        val highThreshold = levels[levelIdx] * 1.7

        when {
            fillMs < lowThreshold && levelIdx < levels.size - 1 -> {
                levelIdx++
                lastChangeMs = now
                android.util.Log.d("Reflex", "Buffer LOW → target=${levels[levelIdx]}ms")
            }
            fillMs > highThreshold && levelIdx > 0 -> {
                levelIdx--
                lastChangeMs = now
                android.util.Log.d("Reflex", "Buffer HIGH → target=${levels[levelIdx]}ms")
            }
        }
    }

    fun getCurrentTargetMs() = levels[levelIdx]
}
