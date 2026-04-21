package com.aichatsdk.audio

import com.aichatsdk.models.CodecType

/**
 * Thin Kotlin wrapper around libopus 1.4 (via [NativeBridge]).
 * 20ms framing — caller must feed exactly [frameSize] samples per encode.
 *
 * For 16 kHz mono → frameSize = 320 samples.
 * For 24 kHz mono → frameSize = 480 samples.
 */
class OpusEncoder(
    val sampleRate: Int,
    val channels: Int = 1,
    bitrate: Int = 32_000,
) : AutoCloseable {
    val frameSize: Int = sampleRate / 50 // 20ms
    private var handle: Long = 0

    init {
        require(NativeBridge.hasOpus) { "Opus native lib not available" }
        handle = NativeBridge.opusEncCreate(sampleRate, channels, bitrate)
        require(handle != 0L) { "opusEncCreate failed" }
    }

    fun encode(pcm: ShortArray): ByteArray? {
        if (handle == 0L) return null
        return NativeBridge.opusEncode(handle, pcm, frameSize)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.opusEncDestroy(handle)
            handle = 0
        }
    }
}

class OpusDecoder(
    val sampleRate: Int,
    val channels: Int = 1,
) : AutoCloseable {
    val frameSize: Int = sampleRate / 50
    private var handle: Long = 0

    init {
        require(NativeBridge.hasOpus) { "Opus native lib not available" }
        handle = NativeBridge.opusDecCreate(sampleRate, channels)
        require(handle != 0L) { "opusDecCreate failed" }
    }

    fun decode(packet: ByteArray): ShortArray? {
        if (handle == 0L) return null
        return NativeBridge.opusDecode(handle, packet, frameSize)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.opusDecDestroy(handle)
            handle = 0
        }
    }
}

/** Convenience helpers used by audio engines. */
internal object OpusFraming {
    /** Number of 16-bit samples in a 20ms frame at the given sample rate. */
    fun frameSamples(sampleRate: Int): Int = sampleRate / 50

    /** Number of bytes per 20ms PCM frame. */
    fun frameBytes(sampleRate: Int): Int = frameSamples(sampleRate) * 2

    fun pickEncoder(codec: CodecType, sampleRate: Int): OpusEncoder? =
        if (codec == CodecType.Opus && NativeBridge.hasOpus)
            OpusEncoder(sampleRate) else null

    fun pickDecoder(codec: CodecType, sampleRate: Int): OpusDecoder? =
        if (codec == CodecType.Opus && NativeBridge.hasOpus)
            OpusDecoder(sampleRate) else null
}
