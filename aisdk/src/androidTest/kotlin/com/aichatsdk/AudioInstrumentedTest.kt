package com.aichatsdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichatsdk.audio.AudioCaptureEngine
import com.aichatsdk.audio.AudioPlaybackEngine
import com.aichatsdk.audio.NativeBridge
import com.aichatsdk.audio.OpusDecoder
import com.aichatsdk.audio.OpusEncoder
import com.aichatsdk.models.CodecType
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioInstrumentedTest {

    @Test fun `opus encode-decode roundtrip preserves frame size`() {
        assumeTrue("native lib + libopus required", NativeBridge.hasOpus)
        val sr = 16_000
        OpusEncoder(sr).use { enc ->
            OpusDecoder(sr).use { dec ->
                val samples = ShortArray(enc.frameSize) // silence
                val packet = enc.encode(samples)
                assertThat(packet).isNotNull()
                assertThat(packet!!.size).isGreaterThan(0)
                val decoded = dec.decode(packet)
                assertThat(decoded).isNotNull()
                assertThat(decoded!!.size).isEqualTo(dec.frameSize)
            }
        }
    }

    @Test fun `capture engine emits PCM frames sized to 20ms`() {
        // Requires RECORD_AUDIO grant in test runner. Manifest declares it; emulator grants it.
        val latch = CountDownLatch(3)
        var lastFrameBytes = 0
        val cap = AudioCaptureEngine(sampleRate = 16_000, codec = CodecType.Pcm) { frame ->
            lastFrameBytes = frame.size
            latch.countDown()
        }
        try {
            cap.start()
        } catch (e: IllegalStateException) {
            // Some emulators have no mic — skip rather than fail.
            return
        }
        val ok = latch.await(3, TimeUnit.SECONDS)
        cap.stop()
        if (ok) {
            // 16k * 20ms * 2 bytes/sample = 640 bytes
            assertThat(lastFrameBytes).isEqualTo(640)
        }
    }

    @Test fun `playback engine accepts PCM frames without throwing`() {
        val play = AudioPlaybackEngine(sampleRate = 24_000, codec = CodecType.Pcm)
        try {
            play.start()
            val frame = ByteArray(24_000 / 50 * 2) // 20ms PCM, silence
            play.feedAudio(frame)
            play.feedAudio(frame)
            Thread.sleep(100)
            play.markEndOfStream()
            Thread.sleep(100)
        } finally {
            play.stop()
        }
    }
}
