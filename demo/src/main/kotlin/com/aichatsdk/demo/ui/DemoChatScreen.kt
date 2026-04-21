package com.aichatsdk.demo.ui

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aichatsdk.demo.DemoApp
import com.aichatsdk.models.ChatTurnState
import com.aichatsdk.models.SpeechConfig
import com.aichatsdk.models.SpeechMode
import com.aichatsdk.models.StopAction
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoChatScreen(app: DemoApp) {
    val service = app.chatService
    val messages by service.messages.collectAsState()
    val asrText by service.speech.currentAsrText.collectAsState()
    val isConnected by service.isTransportConnected.collectAsState()
    val sessionReady by service.sessionReady.collectAsState()
    val turn by service.currentTurn.collectAsState()
    val isSpeechActive by service.speech.isSpeechActive.collectAsState()

    var modality by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver(
        save = { it.name },
        restore = { name -> Modality.values().firstOrNull { it.name == name } ?: Modality.TextToText }
    )) { mutableStateOf(Modality.TextToText) }
    var inCall by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AIChat Demo") },
                actions = {
                    IconButton(onClick = { service.clearHistory() }) {
                        Icon(Icons.Filled.DeleteOutline, "clear")
                    }
                    IconButton(onClick = { showLog = true }) {
                        Icon(Icons.Filled.BugReport, "log")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, "settings")
                    }
                }
            )
        }
    ) { padding ->
        val focusManager = LocalFocusManager.current
        var turnState by remember { mutableStateOf<ChatTurnState?>(null) }
        LaunchedEffect(turn) {
            turnState = null
            turn?.state?.collect { turnState = it }
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { focusManager.clearFocus() }
        ) {
            StatusBar(isConnected, sessionReady, turnState)

            ModalityPicker(modality) { modality = it }

            MessageList(
                messages = if (modality == Modality.PureTts) messages.filter { it.role == com.aichatsdk.models.Role.USER } else messages,
                asrLive = asrText.takeIf { it.isNotBlank() },
                onImageTap = { fullscreenImage = it },
                modifier = Modifier.weight(1f),
            )

            when (val m = modality) {
                Modality.TextToText, Modality.TextToTts -> {
                    TextInputBar(onSend = { text, attachments ->
                        if (text.isBlank() && attachments.isEmpty()) return@TextInputBar
                        service.send(text, attachments, withTts = m == Modality.TextToTts)
                    })
                }
                Modality.PureTts -> {
                    TextInputBar(onSend = { text, _ ->
                        if (text.isNotBlank()) service.synthesize(text)
                    })
                }
                Modality.PureAsr, Modality.VoiceToText, Modality.VoiceToVoice -> {
                    VoiceInputBar(
                        showCallToggle = m == Modality.VoiceToVoice,
                        inCall = inCall,
                        onPttDown = {
                            val cfg = SpeechConfig(
                                mode = m.mode ?: SpeechMode.AsrOnly,
                                inputCodec = app.effectiveCodec(),
                                outputCodec = app.effectiveCodec(),
                                ttsEnabled = m.tts,
                            )
                            service.startSpeech(cfg)
                        },
                        onPttUp = { cancel ->
                            service.stopSpeech(if (cancel) StopAction.Cancel else StopAction.Stop)
                        },
                        onToggleCall = {
                            if (inCall) {
                                service.stopSpeech(StopAction.Stop)
                                inCall = false
                            } else {
                                service.startSpeech(SpeechConfig(
                                    mode = SpeechMode.FullDuplex,
                                    inputCodec = app.settings.snapshot().codec,
                                    outputCodec = app.settings.snapshot().codec,
                                ))
                                inCall = true
                            }
                        },
                    )
                }
            }
        }
    }

    if (showSettings) {
        DemoSettingsScreen(app, onClose = { reconnect ->
            showSettings = false
            if (reconnect) app.connect()
        })
    }
    if (showLog) DebugEventLogScreen(onClose = { showLog = false })
    fullscreenImage?.let { b64 ->
        FullscreenImageDialog(b64 = b64, onDismiss = { fullscreenImage = null })
    }
}

@Composable
private fun StatusBar(isConnected: Boolean, sessionReady: Boolean, turnState: ChatTurnState?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (isConnected) Color(0xFF22C55E) else Color(0xFFEF4444)); Text("WS", fontSize = 12.sp)
        Dot(if (sessionReady) Color(0xFF22C55E) else Color(0xFF9CA3AF)); Text("Session", fontSize = 12.sp)
        val (color, label) = when (turnState) {
            ChatTurnState.AsrListening -> Color(0xFFF59E0B) to "ASR"
            ChatTurnState.AiThinking -> Color(0xFF8B5CF6) to "AI"
            ChatTurnState.AiStreaming -> Color(0xFF3B82F6) to "Stream"
            ChatTurnState.TtsStreaming -> Color(0xFF22C55E) to "TTS"
            ChatTurnState.Cancelled -> Color(0xFFEF4444) to "Cancel"
            else -> Color(0xFFD1D5DB) to "Idle"
        }
        Dot(color); Text(label, fontSize = 12.sp)
    }
}

@Composable private fun Dot(c: Color) {
    Box(Modifier.size(10.dp).background(c, RoundedCornerShape(50)))
}

@Composable
private fun ModalityPicker(current: Modality, onChange: (Modality) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Modality.values().forEach { m ->
            val sel = m == current
            Box(
                Modifier
                    .weight(1f).height(36.dp)
                    .background(if (sel) Color(0xFF1F6FEB) else Color(0xFFE5E7EB), RoundedCornerShape(18.dp))
                    .clickable { onChange(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(m.label, color = if (sel) Color.White else Color(0xFF111827), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FullscreenImageDialog(b64: String, onDismiss: () -> Unit) {
    val bytes = remember(b64) { Base64.decode(b64, Base64.DEFAULT) }
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
            AsyncImage(model = bytes, contentDescription = null, modifier = Modifier.fillMaxWidth())
        }
    }
}
