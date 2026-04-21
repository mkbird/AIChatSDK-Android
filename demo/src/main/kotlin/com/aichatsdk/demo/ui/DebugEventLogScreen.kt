package com.aichatsdk.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aichatsdk.demo.transport.DebugEventLog
import com.aichatsdk.models.ChatEventCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugEventLogScreen(onClose: () -> Unit) {
    var entries by remember { mutableStateOf(DebugEventLog.snapshot()) }
    DisposableEffect(Unit) {
        val cb: (List<DebugEventLog.Entry>) -> Unit = { entries = it }
        DebugEventLog.addListener(cb)
        onDispose { DebugEventLog.removeListener(cb) }
    }
    var detail by remember { mutableStateOf<DebugEventLog.Entry?>(null) }

    Dialog(onDismissRequest = onClose) {
        Surface(Modifier.fillMaxSize(), tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Debug Events") },
                    actions = {
                        TextButton(onClick = { DebugEventLog.clear() }) { Text("Clear") }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "close") }
                    }
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries.reversed()) { entry -> EntryRow(entry) { detail = entry } }
                }
            }
        }
    }
    detail?.let { e ->
        Dialog(onDismissRequest = { detail = null }) {
            Surface(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${e.direction} · ${formatTs(e.ts)}")
                    Spacer(Modifier.height(8.dp))
                    val body = if (e.isAudio) "[binary audio frame, ${e.audioBytes} bytes]"
                               else e.event?.let { ChatEventCodec.toJson(it) } ?: "(no event)"
                    Text(body, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: DebugEventLog.Entry, onClick: () -> Unit) {
    val arrow = if (entry.direction == DebugEventLog.Direction.Sent) "↑" else "↓"
    val color = if (entry.direction == DebugEventLog.Direction.Sent) Color(0xFF1F6FEB) else Color(0xFF22C55E)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(arrow, color = color)
        Text(formatTs(entry.ts), fontSize = 11.sp, color = Color(0xFF6B7280))
        Text(
            text = if (entry.isAudio) "[audio ${entry.audioBytes}B]" else entry.event?.type ?: "?",
            fontSize = 13.sp,
        )
    }
    Divider()
}

private fun formatTs(ts: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ts))
