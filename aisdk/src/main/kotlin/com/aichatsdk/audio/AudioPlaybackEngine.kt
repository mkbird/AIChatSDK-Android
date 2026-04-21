package com.aichatsdk.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.aichatsdk.models.CodecType
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Speaker playback: Opus/PCM frames → AudioTrack (VOICE_COMMUNICATION usage).
 *
 * Uses [AudioTrack.MODE_STREAM]. clearBuffers() mutes-then-flushes-then-unmutes
 * to avoid the "loud crackle" iOS engineers see when stopping/restarting the
 * voice render graph (mirrors the iOS gain-burst workaround).
 *
 * If [apm] is supplied, each PCM frame is fed to APM as a far-end reference
 * just before being written to the speaker, ensuring AEC has correct timing.
 */
class AudioPlaybackEngine(
    val sampleRate: Int = 24_000,
    val codec: CodecType = CodecType.Pcm,
    private val apm: WebRtcApm? = null,
    private val onPlaybackDrained: (() -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val endOfStream = AtomicBoolean(false)
    private val pending = AtomicInteger(0)
    private val queue = LinkedBlockingQueue<ByteArray?>()
    private var track: AudioTrack? = null
    private var decoder: OpusDecoder? = null
    private var thread: Thread? = null

    fun start() {
        if (running.get()) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 25 * 2)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack(
            attrs, format, minBuf * 2,
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release(); throw IllegalStateException("AudioTrack init failed")
        }
        track = t
        if (codec == CodecType.Opus && NativeBridge.hasOpus) {
            decoder = OpusDecoder(sampleRate)
        }
        t.play()
        running.set(true)
        endOfStream.set(false)
        thread = thread(name = "aichat-play", isDaemon = true) { loop(t) }
    }

    private fun loop(t: AudioTrack) {
        val pcm10msSamples = sampleRate / 100
        val far10ms = ShortArray(pcm10msSamples)
        try {
            while (running.get()) {
                val frame = queue.take() ?: break
                val pcm = decodeIfNeeded(frame)
                if (pcm == null) {
                    pending.decrementAndGet()
                    maybeDrained()
                    continue
                }
                // Feed APM far-end reference in 10ms slices BEFORE writing
                if (apm != null) {
                    var idx = 0
                    while (idx + pcm10msSamples <= pcm.size) {
                        System.arraycopy(pcm, idx, far10ms, 0, pcm10msSamples)
                        apm.processReverseStream(far10ms)
                        idx += pcm10msSamples
                    }
                }
                t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                pending.decrementAndGet()
                maybeDrained()
            }
        } catch (_: InterruptedException) {
        } catch (t2: Throwable) {
            Log.e(TAG, "playback loop crashed", t2)
        }
    }

    private fun maybeDrained() {
        if (endOfStream.get() && pending.get() == 0) {
            onPlaybackDrained?.invoke()
            endOfStream.set(false)
        }
    }

    private fun decodeIfNeeded(frame: ByteArray): ShortArray? {
        val dec = decoder
        return if (dec != null) {
            dec.decode(frame)
        } else {
            // raw PCM little-endian → shorts
            val out = ShortArray(frame.size / 2)
            for (i in out.indices) {
                val lo = frame[i * 2].toInt() and 0xFF
                val hi = frame[i * 2 + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort()
            }
            out
        }
    }

    fun feedAudio(data: ByteArray) {
        if (!running.get()) return
        pending.incrementAndGet()
        queue.offer(data)
    }

    fun markEndOfStream() {
        endOfStream.set(true)
        // Wake loop to re-check drain
        if (pending.get() == 0) {
            onPlaybackDrained?.invoke()
            endOfStream.set(false)
        }
    }

    /**
     * Mute → flush → unmute. Mirrors iOS clearBuffers: avoids reattaching the
     * player node so VPIO/AGC won't see a silence→activity gain burst.
     */
    fun clearBuffers() {
        val t = track ?: return
        try {
            t.setVolume(0f)
            queue.clear()
            t.pause()
            t.flush()
            pending.set(0)
            t.play()
            t.setVolume(1f)
        } catch (e: Throwable) {
            Log.w(TAG, "clearBuffers warn", e)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.offer(null) // unblock take()
        try { thread?.join(300) } catch (_: InterruptedException) {}
        thread = null
        track?.runCatching { stop(); release() }
        track = null
        decoder?.close(); decoder = null
        queue.clear()
        pending.set(0)
    }

    companion object { private const val TAG = "AudioPlayback" }
}
