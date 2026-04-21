package com.aichatsdk.audio

import com.aichatsdk.models.CodecType
import com.aichatsdk.models.SpeechConfig

/**
 * Combined capture + playback for full-duplex barge-in. Mirrors iOS
 * FullDuplexAudioEngine.
 *
 * On Android we do NOT have iOS VPIO; instead we run a single shared
 * [WebRtcApm] across capture and playback so AEC3 has both near-end (mic)
 * and far-end (speaker) reference at the right cadence.
 *
 * If [WebRtcApm.available] is false (libaichat_native missing), we degrade
 * gracefully: capture still works, playback still works, but echo will leak
 * into the mic. The SDK reports this via [aecAvailable] so the app can warn
 * the user or force PTT-only.
 */
class FullDuplexAudioEngine(
    val captureSampleRate: Int = 16_000,
    val captureCodec: CodecType = CodecType.Pcm,
    val playbackSampleRate: Int = 24_000,
    val playbackCodec: CodecType = CodecType.Pcm,
) {
    private val apm: WebRtcApm? = WebRtcApm(captureSampleRate).takeIf { it.available }

    /**
     * Note: APM expects matching sample rates between capture and playback.
     * If TTS sample rate ≠ capture, we still feed it (APM will resample
     * internally) — but for ideal AEC use 16k both ways.
     */
    val aecAvailable: Boolean get() = apm?.available == true

    private var capture: AudioCaptureEngine? = null
    private var playback: AudioPlaybackEngine? = null

    private var onCaptureFrame: ((ByteArray) -> Unit)? = null
    private var onDrained: (() -> Unit)? = null

    fun startCapture(onFrame: (ByteArray) -> Unit) {
        onCaptureFrame = onFrame
        if (capture != null) return
        ensurePlayback()
        capture = AudioCaptureEngine(
            sampleRate = captureSampleRate,
            codec = captureCodec,
            apm = apm,
            onFrame = { frame -> onCaptureFrame?.invoke(frame) },
        ).also { it.start() }
    }

    fun rearmCapture(onFrame: (ByteArray) -> Unit) {
        // On Android there's no equivalent "leave-engine-alive" trick; just
        // restart the capture path — the AudioTrack stays alive so playback
        // is not disturbed.
        stopCapture()
        startCapture(onFrame)
    }

    fun stopCapture() {
        capture?.stop()
        capture = null
    }

    fun ensurePlayback() {
        if (playback != null) return
        playback = AudioPlaybackEngine(
            sampleRate = playbackSampleRate,
            codec = playbackCodec,
            apm = apm,
            onPlaybackDrained = { onDrained?.invoke() },
        ).also { it.start() }
    }

    fun setOnPlaybackDrained(cb: () -> Unit) { onDrained = cb }

    fun feedAudio(data: ByteArray) {
        ensurePlayback()
        playback?.feedAudio(data)
    }

    fun markEndOfStream() { playback?.markEndOfStream() }
    fun clearPlayback() { playback?.clearBuffers() }

    fun stopAll() {
        stopCapture()
        playback?.stop()
        playback = null
        apm?.close()
    }

    companion object {
        fun forConfig(cfg: SpeechConfig): FullDuplexAudioEngine =
            FullDuplexAudioEngine(
                captureSampleRate = cfg.inputSampleRate,
                captureCodec = cfg.inputCodec,
                playbackSampleRate = cfg.outputSampleRate,
                playbackCodec = cfg.outputCodec,
            )
    }
}
