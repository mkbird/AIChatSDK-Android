package com.aichatsdk.demo.ui

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aichatsdk.demo.util.AttachmentSupport
import com.aichatsdk.models.ChatAttachment
import kotlinx.coroutines.launch

@Composable
fun TextInputBar(
    onSend: (String, List<ChatAttachment>) -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    val attachments = remember { mutableStateListOf<ChatAttachment>() }

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 3)
    ) { uris: List<Uri> ->
        uris.take(3 - attachments.size).forEach { uri ->
            AttachmentSupport.uriToAttachment(context, uri)?.let { attachments.add(it) }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        // Pre-resized thumbnail from camera intent — encode directly.
        if (bmp != null) {
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
            attachments.add(
                ChatAttachment(
                    kind = "image",
                    data = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP),
                    mimeType = "image/jpeg",
                )
            )
        }
    }

    Column(Modifier.fillMaxWidth().background(Color(0xFFF3F4F6))) {
        if (attachments.isNotEmpty()) {
            LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(attachments.size) { idx ->
                    val att = attachments[idx]
                    val bytes = remember(att.data) { Base64.decode(att.data, Base64.DEFAULT) }
                    Box(Modifier.size(64.dp)) {
                        AsyncImage(
                            model = bytes,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                        )
                        IconButton(
                            onClick = { attachments.removeAt(idx) },
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.TopEnd)
                                .background(Color(0x99000000), CircleShape),
                        ) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                    }
                }
            }
        }

        if (showAttach) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = {
                    pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    ))
                }) { Icon(Icons.Filled.PhotoLibrary, "library") }
                IconButton(onClick = { takePhoto.launch(null) }) { Icon(Icons.Filled.PhotoCamera, "camera") }
            }
        }

        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showAttach = !showAttach }) {
                Icon(Icons.Filled.Add, contentDescription = "attach")
            }
            Box(
                Modifier
                    .weight(1f)
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (input.isEmpty()) Text("说点什么…", color = Color(0xFF9CA3AF), fontSize = 14.sp)
            }
            IconButton(
                onClick = {
                    if (enabled && (input.isNotBlank() || attachments.isNotEmpty())) {
                        onSend(input.trim(), attachments.toList())
                        input = ""
                        attachments.clear()
                        showAttach = false
                    }
                },
                enabled = enabled && (input.isNotBlank() || attachments.isNotEmpty()),
            ) { Icon(Icons.Filled.Send, "send", tint = if (enabled) Color(0xFF1F6FEB) else Color.Gray) }
        }
    }
}
