package com.aichatsdk.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aichatsdk.models.CodecType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Microphone capture: AudioRecord (VOICE_COMMUNICATION) → 20ms frames →
 * optional Opus encode → onFrame callback.
 *
 * Mirrors iOS AudioCaptureEngine. Frames are emitted on the capture thread.
 *
 * If [apm] is supplied, every captured 10ms half-frame goes through APM in
 * place before being concatenated to the 20ms output frame.
 */
class AudioCaptureEngine(
    val sampleRate: Int = 16_000,
    val codec: CodecType = CodecType.Pcm,
    private val apm: WebRtcApm? = null,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var encoder: OpusEncoder? = null
    private var thread: Thread? = null

    /** APM session id (for system AudioEffect chaining if APM not used). */
    val audioSessionId: Int? get() = record?.audioSessionId

    @Throws(IllegalStateException::class)
    fun start() {
        if (running.get()) return
        val frame20 = OpusFraming.frameSamples(sampleRate)            // 320 @16k
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frame20 * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed")
        }
        record = rec
        if (codec == CodecType.Opus && NativeBridge.hasOpus) {
            encoder = OpusEncoder(sampleRate)
            Log.i(TAG, "Opus encoder created, sampleRate=$sampleRate")
        } else {
            Log.w(TAG, "Opus not available (hasOpus=${NativeBridge.hasOpus}), using PCM")
        }
        rec.startRecording()
        running.set(true)
        thread = thread(name = "aichat-cap", isDaemon = true) { loop(rec) }
    }

    private fun loop(rec: AudioRecord) {
        val frame20 = OpusFraming.frameSamples(sampleRate)
        val frame10 = frame20 / 2
        val acc = ShortArray(frame20)
        val half = ShortArray(frame10)
        var accFill = 0
        try {
            while (running.get()) {
                val n = rec.read(half, 0, frame10, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                // APM operates on 10ms frames in place
                apm?.processStream(half)
                System.arraycopy(half, 0, acc, accFill, n)
                accFill += n
                if (accFill >= frame20) {
                    emit(acc)
                    accFill = 0
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture loop crashed", t)
        }
    }

    private fun emit(pcm20ms: ShortArray) {
        val maxAmp = pcm20ms.maxOf { it.toInt().let { v -> if (v < 0) -v else v } }
        if (maxAmp < 10) Log.w(TAG, "capture: near-silent frame maxAmp=$maxAmp")
        // Software gain when APM AGC2 is not available (APM handles it otherwise)
        if (apm == null) applyGain(pcm20ms, gain = 4.0f)
        val enc = encoder
        if (enc != null) {
            enc.encode(pcm20ms)?.let(onFrame)
        } else {
            val out = ByteArray(pcm20ms.size * 2)
            for (i in pcm20ms.indices) {
                val s = pcm20ms[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            onFrame(out)
        }
    }

    private fun applyGain(pcm: ShortArray, gain: Float) {
        for (i in pcm.indices) {
            pcm[i] = (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        record?.runCatching { stop(); release() }
        record = null
        encoder?.close()
        encoder = null
    }

    companion object { private const val TAG = "AudioCapture" }
}
