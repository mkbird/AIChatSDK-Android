package com.aichatsdk.audio

/**
 * JNI bridge to libaichat_native.so.
 *
 * Library load is non-fatal — apps can still use the SDK in PCM/PTT mode if the
 * native library is missing (e.g. unsupported ABI, restrictive SELinux). Check
 * [isAvailable] / [hasOpus] / [hasApm] before requesting Opus or full-duplex AEC.
 */
internal object NativeBridge {
    @Volatile private var loaded = false
    @Volatile private var loadError: Throwable? = null

    init { tryLoad() }

    private fun tryLoad() {
        if (loaded) return
        try {
            System.loadLibrary("aichat_native")
            loaded = true
        } catch (t: Throwable) {
            loadError = t
            loaded = false
        }
    }

    val isAvailable: Boolean get() = loaded
    val loadFailure: Throwable? get() = loadError
    val hasOpus: Boolean get() = loaded && haveOpus()
    val hasApm: Boolean get() = loaded && haveApm()

    @JvmStatic external fun haveOpus(): Boolean
    @JvmStatic external fun haveApm(): Boolean

    // ----- Opus -----
    @JvmStatic external fun opusEncCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    @JvmStatic external fun opusEncode(handle: Long, pcm: ShortArray, frameSize: Int): ByteArray?
    @JvmStatic external fun opusEncDestroy(handle: Long)

    @JvmStatic external fun opusDecCreate(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun opusDecode(handle: Long, opus: ByteArray, frameSize: Int): ShortArray?
    @JvmStatic external fun opusDecDestroy(handle: Long)

    // ----- WebRTC APM -----
    @JvmStatic external fun apmCreate(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun apmProcessStream(handle: Long, pcm: ShortArray, frameSize: Int): Int
    @JvmStatic external fun apmProcessReverseStream(handle: Long, pcm: ShortArray, frameSize: Int): Int
    @JvmStatic external fun apmDestroy(handle: Long)
}
