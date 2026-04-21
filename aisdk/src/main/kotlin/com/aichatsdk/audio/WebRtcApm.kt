package com.aichatsdk.audio

/**
 * Wrapper around the WebRTC AudioProcessing module (AEC3 + NS + AGC2).
 * Operates on 10ms frames at the configured sample rate (mono only).
 *
 * Usage pattern (AEC):
 *   1. Before [AudioTrack.write], call [processReverseStream] with the
 *      same PCM about to play (far-end reference).
 *   2. After [AudioRecord.read], call [processStream] in-place to remove
 *      echo + suppress noise + apply AGC.
 *
 * If WebRTC APM was not compiled in (submodule missing), [available] is false
 * and all methods are no-ops.
 */
class WebRtcApm(
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
) : AutoCloseable {

    val frameSize: Int = sampleRate / 100 // 10ms (APM requires 10ms)
    private var handle: Long = 0
    val available: Boolean get() = handle != 0L

    init {
        if (NativeBridge.hasApm) {
            handle = NativeBridge.apmCreate(sampleRate, channels)
        }
    }

    /** Process a 10ms near-end (mic) frame in place. */
    fun processStream(pcm: ShortArray): Int {
        if (handle == 0L) return -1
        return NativeBridge.apmProcessStream(handle, pcm, frameSize)
    }

    /** Process a 10ms far-end (speaker) reference frame in place. */
    fun processReverseStream(pcm: ShortArray): Int {
        if (handle == 0L) return -1
        return NativeBridge.apmProcessReverseStream(handle, pcm, frameSize)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.apmDestroy(handle)
            handle = 0
        }
    }
}
