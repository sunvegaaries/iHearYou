// android-receiver/app/src/main/java/com/audioshare/core/buffer/JitterBuffer.kt
package com.audioshare.core.buffer

import java.util.concurrent.atomic.AtomicBoolean

data class AudioPacket(
    val seq: Long,
    val timestamp: Long,
    val data: ByteArray
)

enum class BufferEvent { LOW, HIGH }

/**
 * Packet-level jitter buffer.
 * Fixed circular array — zero dynamic allocations in hot path.
 * Single writer (UDP thread), single reader (DecodeThread).
 * Drop oldest on overflow — NEVER blocks.
 */
class JitterBuffer(private val targetMs: Int = 200) {

    private val capacity = 64
    private val buf = arrayOfNulls<AudioPacket>(capacity)

    @Volatile private var write = 0
    @Volatile private var read  = 0
    @Volatile private var size  = 0  // current packet count

    // Lightweight events — atomic flags, no Flow
    val hasLow  = AtomicBoolean(false)
    val hasHigh = AtomicBoolean(false)

    // Loss tracking
    private var expectedSeq = -1L
    private var lostInWindow = 0
    private var totalInWindow = 0
    private var windowStart = System.currentTimeMillis()
    private val windowMs = 5000L

    /** Called from UDP receive coroutine */
    fun push(packet: AudioPacket) {
        updateLoss(packet.seq)

        synchronized(this) {
            // Drop oldest if full
            if (size >= capacity - 1) {
                read = (read + 1) % capacity
                size--
            }
            buf[write] = packet
            write = (write + 1) % capacity
            size++
        }

        checkHealth()
    }

    /** Called from DecodeThread — returns null if empty */
    fun pop(): AudioPacket? {
        synchronized(this) {
            if (size == 0) return null
            val p = buf[read]
            buf[read] = null
            read = (read + 1) % capacity
            size--
            return p
        }
    }

    fun getFillMs(): Int = size * 20  // 20ms per frame

    /** For CodecPolicy — rolling loss % */
    fun getLossPercent(): Float {
        val now = System.currentTimeMillis()
        if (now - windowStart > windowMs) {
            lostInWindow  = 0
            totalInWindow = 0
            windowStart   = now
        }
        return if (totalInWindow > 0)
            (lostInWindow.toFloat() / totalInWindow) * 100f
        else 0f
    }

    private fun updateLoss(seq: Long) {
        val now = System.currentTimeMillis()
        if (now - windowStart > windowMs) {
            lostInWindow  = 0
            totalInWindow = 0
            windowStart   = now
        }
        totalInWindow++
        if (expectedSeq >= 0 && seq > expectedSeq) {
            lostInWindow += (seq - expectedSeq).toInt().coerceAtMost(10)
        }
        expectedSeq = seq + 1
    }

    private fun checkHealth() {
        val fillMs = getFillMs()
        when {
            fillMs < 60                    -> hasLow.set(true)
            fillMs > targetMs * 1.7        -> hasHigh.set(true)
        }
    }
}
