// android-receiver/app/src/main/java/com/audioshare/core/audio/AudioPipeline.kt
package com.audioshare.core.audio

/**
 * Thin wrapper around AAudio stream (via JNI).
 * Start/stop only. The actual audio callback lives in C++ (audioshare_bridge.cpp).
 * DecodeThread writes PCM directly into the C++ ring buffer.
 */
class AudioPipeline {

    private var running = false

    fun start(): Boolean {
        if (running) return true
        val ok = nativeStartStream()
        if (ok) running = true
        return ok
    }

    fun stop() {
        if (!running) return
        nativeStopStream()
        running = false
    }

    fun isRunning() = running

    /** Returns PCM ring fill in milliseconds (for monitoring) */
    fun getFillMs(): Int = nativeGetFillMs()

    private external fun nativeStartStream(): Boolean
    private external fun nativeStopStream()
    private external fun nativeGetFillMs(): Int

    companion object {
        init {
            System.loadLibrary("audioshare")
        }
    }
}
