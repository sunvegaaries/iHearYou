// android-receiver/app/src/main/java/com/audioshare/core/buffer/PcmRingBuffer.kt
package com.audioshare.core.buffer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed circular PCM buffer.
 * DecodeThread writes → AAudio callback reads (dumb pull only).
 * Drop oldest on overflow — NEVER blocks writer.
 * Zero allocations in hot path.
 */
class PcmRingBuffer(
    val frameSize: Int = 960,    // 20ms @ 48kHz mono
    val maxFrames: Int = 32      // ~640ms max depth
) {
    // Fixed array — allocated once at creation
    private val storage = ShortArray(maxFrames * frameSize)

    private val writeIdx = AtomicInteger(0)  // in frames
    private val readIdx  = AtomicInteger(0)  // in frames
    private val count    = AtomicInteger(0)  // current frame count

    /** DecodeThread: write one decoded PCM frame. Never blocks. */
    fun writeFrame(pcm: ShortArray) {
        require(pcm.size == frameSize) { "Frame size mismatch" }

        // Drop oldest if full
        if (count.get() >= maxFrames - 1) {
            readIdx.incrementAndGet()
            count.decrementAndGet()
        }

        val wi = writeIdx.getAndIncrement() % maxFrames
        System.arraycopy(pcm, 0, storage, wi * frameSize, frameSize)
        count.incrementAndGet()
    }

    /**
     * AAudio callback: pull one frame into output buffer.
     * Returns false if empty (caller should output silence).
     */
    fun readFrame(output: ShortArray): Boolean {
        if (count.get() == 0) return false

        val ri = readIdx.getAndIncrement() % maxFrames
        System.arraycopy(storage, ri * frameSize, output, 0, frameSize)
        count.decrementAndGet()
        return true
    }

    fun getFillFrames(): Int = count.get()
    fun getFillMs(): Int = count.get() * 20

    /** Call on session reset / reconnect */
    fun clear() {
        writeIdx.set(0)
        readIdx.set(0)
        count.set(0)
    }
}
