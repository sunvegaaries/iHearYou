// android-receiver/app/src/main/java/com/audioshare/core/audio/DecodeThread.kt
package com.audioshare.core.audio

import com.audioshare.core.buffer.JitterBuffer
import com.audioshare.core.buffer.PcmRingBuffer

/**
 * Dedicated thread: pops packets from JitterBuffer → decodes Opus → writes to PcmRingBuffer.
 * Never touches the AAudio callback thread.
 * Opus decoder lives entirely here.
 */
class DecodeThread(
    private val jitterBuffer: JitterBuffer,
    private val pcmRing: PcmRingBuffer
) {
    private var thread: Thread? = null
    @Volatile private var running = false

    // JNI — implemented in native/opus_bridge.cpp
    private external fun nativeCreateDecoder(): Long
    private external fun nativeDestroyDecoder(handle: Long)
    /**
     * Decode opus frame with PLC support.
     * If opusData is null/empty → PLC (concealment).
     * Returns number of samples decoded, or negative on error.
     */
    private external fun nativeDecode(
        handle: Long,
        opusData: ByteArray?,
        opusLen: Int,
        pcmOut: ShortArray,
        frameSize: Int,
        fec: Int
    ): Int

    private var decoderHandle: Long = 0

    fun start() {
        decoderHandle = nativeCreateDecoder()
        if (decoderHandle == 0L) {
            System.err.println("[DecodeThread] Failed to create Opus decoder!")
            return
        }
        running = true
        thread = Thread({
            decodeLoop()
        }, "AudioShare-DecodeThread").also {
            it.priority = Thread.MAX_PRIORITY - 1
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        if (decoderHandle != 0L) {
            nativeDestroyDecoder(decoderHandle)
            decoderHandle = 0
        }
    }

    private fun decodeLoop() {
        val pcmOut = ShortArray(pcmRing.frameSize)

        while (running) {
            val packet = jitterBuffer.pop()

            if (packet == null) {
                // Buffer empty — PLC (conceal)
                val n = nativeDecode(decoderHandle, null, 0,
                                     pcmOut, pcmRing.frameSize, 0)
                if (n > 0) pcmRing.writeFrame(pcmOut)
                // Small sleep to avoid spin when completely dry
                Thread.sleep(5)
                continue
            }

            // Try FEC first if buffer is running low
            val useFec = if (jitterBuffer.getFillMs() < 40) 1 else 0

            val n = nativeDecode(decoderHandle, packet.data, packet.data.size,
                                 pcmOut, pcmRing.frameSize, useFec)
            if (n > 0) {
                pcmRing.writeFrame(pcmOut)
            } else {
                System.err.println("[DecodeThread] Opus decode error: $n")
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("audioshare")
        }
    }
}
