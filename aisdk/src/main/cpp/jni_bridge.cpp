// JNI bridge for AIChatSDK Android.
// Exposes:
//   - Opus encode/decode (libopus 1.4)
//   - WebRTC AudioProcessing (AEC3 + NS + AGC2)
//
// All native handles are returned as jlong (raw pointer cast). Kotlin owns
// lifetime via NativeBridge.kt. If a backend isn't compiled in (submodule
// missing), the corresponding *Create returns 0 and Kotlin falls back.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <vector>
#include <memory>

#define LOG_TAG "aichat_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if AICHAT_HAVE_OPUS
extern "C" {
#include "opus.h"
}
#endif

#if AICHAT_HAVE_APM
#include "modules/audio_processing/include/audio_processing.h"
using webrtc::AudioProcessing;
using webrtc::AudioProcessingBuilder;
using webrtc::StreamConfig;
#endif

extern "C" {

// =============================== Opus ===============================

JNIEXPORT jlong JNICALL
Java_com_aichatsdk_audio_NativeBridge_opusEncCreate(
        JNIEnv*, jclass, jint sampleRate, jint channels, jint bitrate) {
#if AICHAT_HAVE_OPUS
    int err = 0;
    OpusEncoder* enc = opus_encoder_create(sampleRate, channels,
                                           OPUS_APPLICATION_RESTRICTED_LOWDELAY, &err);
    if (err != OPUS_OK || !enc) {
        LOGE("opus_encoder_create failed: %d", err);
        return 0;
    }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_VBR(0));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(0));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));
    return reinterpret_cast<jlong>(enc);
#else
    return 0;
#endif
}

JNIEXPORT jbyteArray JNICALL
Java_com_aichatsdk_audio_NativeBridge_opusEncode(
        JNIEnv* env, jclass, jlong handle, jshortArray pcm, jint frameSize) {
#if AICHAT_HAVE_OPUS
    OpusEncoder* enc = reinterpret_cast<OpusEncoder*>(handle);
    if (!enc) return nullptr;
    jsize len = env->GetArrayLength(pcm);
    if (len < frameSize) return nullptr;
    std::vector<int16_t> in(frameSize);
    env->GetShortArrayRegion(pcm, 0, frameSize, in.data());
    std::vector<uint8_t> out(4000);
    int n = opus_encode(enc, in.data(), frameSize, out.data(), out.size());
    if (n <= 0) {
        LOGE("opus_encode failed: %d", n);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(n);
    env->SetByteArrayRegion(result, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return result;
#else
    return nullptr;
#endif
}

JNIEXPORT void JNICALL
Java_com_aichatsdk_audio_NativeBridge_opusEncDestroy(JNIEnv*, jclass, jlong handle) {
#if AICHAT_HAVE_OPUS
    if (handle) opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(handle));
#endif
}

JNIEXPORT jlong JNICALL
Java_com_aichatsdk_audio_NativeBridge_opusDecCreate(
        JNIEnv*, jclass, jint sampleRate, jint channels) {
#if AICHAT_HAVE_OPUS
    int err = 0;
    OpusDecoder* dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || !dec) {
        LOGE("opus_decoder_create failed: %d", err);
        return 0;
    }
    return reinterpret_cast<jlong>(dec);
#else
    return 0;
#endif
}

JNIEXPORT jshortArray JNICALL
Java_com_aichatsdk_audio_NativeBridge_opusDecode(
        JNIEnv* env, jclass, jlong handle, jbyteArray opus, jint frameSize) {
#if AICHAT_HAVE_OPUS
    OpusDecoder* dec = reinterpret_cast<OpusDecoder*>(handle);
    if (!dec) return nullptr;
    jsize len = env->GetArrayLength(opus);
    std::vector<uint8_t> in(len);
    env->GetByteArrayRegion(opus, 0, len, reinterpret_cast<jbyte*>(in.data()));
    std::vector<int16_t> out(frameSize);
    int n = opus_decode(dec, in.data(), len, out.data(), frameSize, 0);
    if (n <= 0) {
        LOGE("opus_decode failed: %d", n);
        return nullptr;
    }
    jshortArray result = env->NewShortArray(n);
    env->SetShortArrayRegion(result, 0, n, out.data());
    return result;
#else
    return nullptr;
#endif
}

JNIEXPORT void JNICALL
Java_com_aichatsdk_audio_NativeBridge_opusDecDestroy(JNIEnv*, jclass, jlong handle) {
#if AICHAT_HAVE_OPUS
    if (handle) opus_decoder_destroy(reinterpret_cast<OpusDecoder*>(handle));
#endif
}

// =============================== WebRTC APM ===============================

#if AICHAT_HAVE_APM
struct ApmCtx {
    std::unique_ptr<AudioProcessing> apm;
    int sampleRate;
    int channels;
};
#endif

JNIEXPORT jlong JNICALL
Java_com_aichatsdk_audio_NativeBridge_apmCreate(
        JNIEnv*, jclass, jint sampleRate, jint channels) {
#if AICHAT_HAVE_APM
    auto* ctx = new ApmCtx();
    ctx->sampleRate = sampleRate;
    ctx->channels = channels;
    ctx->apm.reset(AudioProcessingBuilder().Create());
    if (!ctx->apm) { delete ctx; return 0; }
    AudioProcessing::Config cfg = ctx->apm->GetConfig();
    cfg.echo_canceller.enabled = true;
    cfg.echo_canceller.mobile_mode = true;
    cfg.noise_suppression.enabled = true;
    cfg.noise_suppression.level = AudioProcessing::Config::NoiseSuppression::kModerate;
    cfg.gain_controller2.enabled = true;
    cfg.high_pass_filter.enabled = true;
    ctx->apm->ApplyConfig(cfg);
    return reinterpret_cast<jlong>(ctx);
#else
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_com_aichatsdk_audio_NativeBridge_apmProcessStream(
        JNIEnv* env, jclass, jlong handle, jshortArray pcm, jint frameSize) {
#if AICHAT_HAVE_APM
    auto* ctx = reinterpret_cast<ApmCtx*>(handle);
    if (!ctx) return -1;
    std::vector<int16_t> buf(frameSize);
    env->GetShortArrayRegion(pcm, 0, frameSize, buf.data());
    StreamConfig sc(ctx->sampleRate, ctx->channels);
    int err = ctx->apm->ProcessStream(buf.data(), sc, sc, buf.data());
    env->SetShortArrayRegion(pcm, 0, frameSize, buf.data());
    return err;
#else
    return -1;
#endif
}

JNIEXPORT jint JNICALL
Java_com_aichatsdk_audio_NativeBridge_apmProcessReverseStream(
        JNIEnv* env, jclass, jlong handle, jshortArray pcm, jint frameSize) {
#if AICHAT_HAVE_APM
    auto* ctx = reinterpret_cast<ApmCtx*>(handle);
    if (!ctx) return -1;
    std::vector<int16_t> buf(frameSize);
    env->GetShortArrayRegion(pcm, 0, frameSize, buf.data());
    StreamConfig sc(ctx->sampleRate, ctx->channels);
    return ctx->apm->ProcessReverseStream(buf.data(), sc, sc, buf.data());
#else
    return -1;
#endif
}

JNIEXPORT void JNICALL
Java_com_aichatsdk_audio_NativeBridge_apmDestroy(JNIEnv*, jclass, jlong handle) {
#if AICHAT_HAVE_APM
    auto* ctx = reinterpret_cast<ApmCtx*>(handle);
    delete ctx;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_aichatsdk_audio_NativeBridge_haveOpus(JNIEnv*, jclass) {
#if AICHAT_HAVE_OPUS
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_aichatsdk_audio_NativeBridge_haveApm(JNIEnv*, jclass) {
#if AICHAT_HAVE_APM
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

} // extern "C"
