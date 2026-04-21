package com.aichatsdk.demo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aichatsdk.demo.DemoApp
import com.aichatsdk.models.CodecType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoSettingsScreen(app: DemoApp, onClose: (reconnect: Boolean) -> Unit) {
    val s by app.settings.state.collectAsState()
    var server by remember(s.serverURL) { mutableStateOf(s.serverURL) }
    var token by remember(s.token) { mutableStateOf(s.token) }
    var family by remember(s.familyId) { mutableStateOf(s.familyId) }
    var codec by remember(s.codec) { mutableStateOf(s.codec) }

    Dialog(onDismissRequest = { onClose(false) }) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("设置", style = MaterialTheme.typography.titleLarge)
                Field("服务器 URL", server) { server = it }
                Field("Access Token (JWT)", token) { token = it }
                Field("Family ID", family) { family = it }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Codec")
                    Spacer(Modifier.width(12.dp))
                    AssistChip(
                        onClick = { codec = if (codec == CodecType.Pcm) CodecType.Opus else CodecType.Pcm },
                        label = { Text(codec.wire.uppercase()) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        app.settings.reset(); onClose(true)
                    }) { Text("恢复默认") }
                    Button(onClick = {
                        app.settings.update(server, token, family, codec); onClose(true)
                    }) { Text("保存") }
                }
                Text("SDK 1.0.0 · Protocol v4", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
