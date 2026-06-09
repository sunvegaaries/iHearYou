// android-receiver/app/src/main/cpp/audioshare_bridge.cpp
// JNI bridge: Opus decoder + AAudio output
#include <jni.h>
#include <opus/opus.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <cstring>
#include <atomic>

#define LOG_TAG "AudioShare"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- PCM ring buffer shared between callback and DecodeThread ---
// Simple fixed ring, accessed by AAudio callback (reader) and JNI writeFrame (writer).
// We keep it in C++ for zero-overhead callback access.

static constexpr int FRAME_SIZE   = 960;   // 20ms @ 48kHz mono
static constexpr int RING_FRAMES  = 32;    // ~640ms
static constexpr int RING_SAMPLES = RING_FRAMES * FRAME_SIZE;

static short   g_ring[RING_SAMPLES] = {};
static std::atomic<int> g_write{0};   // in frames
static std::atomic<int> g_read{0};
static std::atomic<int> g_count{0};

static AAudioStream* g_stream = nullptr;

// --- AAudio data callback (called from AAudio thread — must be fast) ---
static aaudio_data_callback_result_t audioCallback(
        AAudioStream* /*stream*/,
        void* /*userData*/,
        void* audioData,
        int32_t numFrames)
{
    auto* out = static_cast<short*>(audioData);
    int needed = numFrames;

    while (needed > 0) {
        if (g_count.load(std::memory_order_acquire) == 0) {
            // Underrun: output silence
            std::memset(out, 0, needed * sizeof(short));
            break;
        }
        int ri = g_read.fetch_add(1, std::memory_order_acq_rel) % RING_FRAMES;
        std::memcpy(out, g_ring + ri * FRAME_SIZE,
                    FRAME_SIZE * sizeof(short));
        g_count.fetch_sub(1, std::memory_order_release);
        out     += FRAME_SIZE;
        needed  -= FRAME_SIZE;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// ===================== JNI: Opus decoder =====================

extern "C"
JNIEXPORT jlong JNICALL
Java_com_audioshare_core_audio_DecodeThread_nativeCreateDecoder(JNIEnv*, jobject) {
    int err = 0;
    OpusDecoder* dec = opus_decoder_create(48000, 1, &err);
    if (err != OPUS_OK || !dec) {
        LOGE("opus_decoder_create failed: %s", opus_strerror(err));
        return 0L;
    }
    LOGI("Opus decoder created (48kHz mono)");
    return reinterpret_cast<jlong>(dec);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_audioshare_core_audio_DecodeThread_nativeDestroyDecoder(
        JNIEnv*, jobject, jlong handle) {
    auto* dec = reinterpret_cast<OpusDecoder*>(handle);
    if (dec) opus_decoder_destroy(dec);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_audioshare_core_audio_DecodeThread_nativeDecode(
        JNIEnv* env, jobject,
        jlong handle,
        jbyteArray opusData, jint opusLen,
        jshortArray pcmOut, jint frameSize,
        jint fec)
{
    auto* dec = reinterpret_cast<OpusDecoder*>(handle);
    if (!dec) return -1;

    jshort* pcm = env->GetShortArrayElements(pcmOut, nullptr);

    int n;
    if (opusData == nullptr || opusLen == 0) {
        // PLC
        n = opus_decode(dec, nullptr, 0,
                        reinterpret_cast<opus_int16*>(pcm), frameSize, 0);
    } else {
        jbyte* data = env->GetByteArrayElements(opusData, nullptr);
        n = opus_decode(dec,
                        reinterpret_cast<const unsigned char*>(data), opusLen,
                        reinterpret_cast<opus_int16*>(pcm), frameSize, fec);
        env->ReleaseByteArrayElements(opusData, data, JNI_ABORT);
    }

    // Write decoded frame directly into C++ PCM ring (faster than going through Kotlin)
    if (n > 0 && n <= FRAME_SIZE) {
        if (g_count.load() >= RING_FRAMES - 1) {
            // Drop oldest
            g_read.fetch_add(1);
            g_count.fetch_sub(1);
        }
        int wi = g_write.fetch_add(1) % RING_FRAMES;
        std::memcpy(g_ring + wi * FRAME_SIZE, pcm, n * sizeof(short));
        g_count.fetch_add(1);
    }

    env->ReleaseShortArrayElements(pcmOut, pcm, 0);
    return n;
}

// ===================== JNI: AAudio stream =====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_audioshare_core_audio_AudioPipeline_nativeStartStream(JNIEnv*, jobject) {
    if (g_stream) return JNI_TRUE; // already running

    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);

    AAudioStreamBuilder_setFormat(builder,        AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder,    48000);
    AAudioStreamBuilder_setChannelCount(builder,  1);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder,   AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(builder,  audioCallback, nullptr);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, FRAME_SIZE);

    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &g_stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGE("AAudioStreamBuilder_openStream failed: %s", AAudio_convertResultToText(result));
        g_stream = nullptr;
        return JNI_FALSE;
    }

    result = AAudioStream_requestStart(g_stream);
    if (result != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed: %s", AAudio_convertResultToText(result));
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        return JNI_FALSE;
    }

    LOGI("AAudio stream started (48kHz mono, low latency)");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_audioshare_core_audio_AudioPipeline_nativeStopStream(JNIEnv*, jobject) {
    if (g_stream) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
    // Reset ring
    g_write.store(0); g_read.store(0); g_count.store(0);
    std::memset(g_ring, 0, sizeof(g_ring));
    LOGI("AAudio stream stopped");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_audioshare_core_audio_AudioPipeline_nativeGetFillMs(JNIEnv*, jobject) {
    return g_count.load() * 20;
}
