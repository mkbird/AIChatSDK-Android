package com.aichatsdk.models

enum class SpeechMode(val wire: String) {
    FullDuplex("full_duplex"),
    AsrOnly("asr_only"),
    PureAsr("pure_asr");

    companion object {
        fun fromWire(s: String): SpeechMode =
            values().firstOrNull { it.wire == s } ?: FullDuplex
    }
}

enum class CodecType(val wire: String) {
    Pcm("pcm"),
    Opus("opus");

    companion object {
        /**
         * Default is Opus — unknown/blank values should favour the higher-quality
         * codec. AudioCaptureEngine will transparently fall back to PCM at runtime
         * if the native Opus encoder is unavailable.
         */
        fun fromWire(s: String?): CodecType =
            if (s.isNullOrBlank()) Opus
            else values().firstOrNull { it.wire == s } ?: Opus
    }
}

data class SpeechConfig(
    val mode: SpeechMode = SpeechMode.FullDuplex,
    val inputCodec: CodecType = CodecType.Pcm,
    val outputCodec: CodecType = CodecType.Pcm,
    val ttsEnabled: Boolean = true,
    val inputSampleRate: Int = 16_000,
    val outputSampleRate: Int = 24_000,
)

enum class StopAction(val wire: String) { Stop("stop"), Cancel("cancel") }
