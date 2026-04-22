package com.aichatsdk.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichatsdk.models.SpeechMode

/**
 * Voice input bar.
 *  - PTT: hold to speak (drag-out cancels). Triggers speech.start with mode=AsrOnly or FullDuplex.
 *  - Call toggle: only shown for FullDuplex modality. Tap to start/stop full-duplex session.
 */
@Composable
fun VoiceInputBar(
    showCallToggle: Boolean,
    inCall: Boolean,
    onPttDown: () -> Unit,
    onPttUp: (cancel: Boolean) -> Unit,
    onToggleCall: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnPttDown by rememberUpdatedState(onPttDown)
    val currentOnPttUp by rememberUpdatedState(onPttUp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F4F6))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(48.dp)
                .background(if (pressed) Color(0xFFEF4444) else Color.White, RoundedCornerShape(24.dp))
                .pointerInput(inCall) {
                    if (inCall) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            currentOnPttDown()
                            val released = tryAwaitRelease()
                            pressed = false
                            currentOnPttUp(!released)
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    inCall -> "通话中…"
                    pressed -> "松开停止"
                    else -> "按住说话"
                },
                color = if (pressed) Color.White else Color(0xFF111827),
                fontSize = 15.sp,
            )
        }
        if (showCallToggle) {
            Button(
                onClick = onToggleCall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (inCall) Color(0xFFEF4444) else Color(0xFF22C55E),
                ),
            ) { Text(if (inCall) "挂断" else "通话") }
        }
    }
}

enum class Modality(val label: String, val mode: SpeechMode?, val tts: Boolean, val voice: Boolean, val ttsOnly: Boolean = false) {
    TextToText("文字→文字", null, false, false),
    TextToTts("文字→TTS", null, true, false),
    PureTts("纯 TTS", null, true, false, ttsOnly = true),
    PureAsr("纯 ASR", SpeechMode.PureAsr, false, true),
    VoiceToText("语音→文字", SpeechMode.AsrOnly, false, true),
    VoiceToVoice("语音→语音", SpeechMode.FullDuplex, true, true),
}
