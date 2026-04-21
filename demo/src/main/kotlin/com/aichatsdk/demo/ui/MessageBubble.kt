package com.aichatsdk.demo.ui

import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aichatsdk.models.ChatMessage
import com.aichatsdk.models.Role
import kotlinx.coroutines.delay

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    asrLive: String?,
    onImageTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + (if (!asrLive.isNullOrBlank()) 1 else 0)
    LaunchedEffect(itemCount, messages.lastOrNull()?.text, asrLive) {
        if (itemCount > 0) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (!asrLive.isNullOrBlank()) item(key = "asr-live") {
            Row(
                Modifier.fillMaxWidth().alpha(0.7f),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .background(Color(0xFF1F6FEB), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(asrLive, color = Color.White, fontSize = 15.sp)
                }
            }
        }
        items(messages.reversed(), key = { it.id }) { msg -> MessageBubble(msg, onImageTap) }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, onImageTap: (String) -> Unit) {
    val isUser = msg.role == Role.USER
    val bg = if (isUser) Color(0xFF1F6FEB) else Color(0xFFE5E7EB)
    val fg = if (isUser) Color.White else Color(0xFF111827)

    val hasImages = msg.attachments.any { it.kind == "image" }
    val showBubble = msg.text.isNotBlank() || (!isUser && !hasImages)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (showBubble) {
            Column(
                Modifier
                    .widthIn(max = 280.dp)
                    .background(bg, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (!isUser && msg.text.isBlank()) {
                    TypingDots()
                } else {
                    if (msg.text.isNotBlank()) Text(msg.text, color = fg, fontSize = 15.sp)
                }
            }
        }
        if (hasImages) {
            Column(
                Modifier.widthIn(max = 280.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                msg.attachments.filter { it.kind == "image" }.forEach { att ->
                    val bytes = remember(att.data) {
                        runCatching { Base64.decode(att.data, Base64.DEFAULT) }.getOrNull()
                    }
                    if (bytes != null) {
                        AsyncImage(
                            model = bytes,
                            contentDescription = null,
                            modifier = Modifier
                                .size(160.dp)
                                .padding(bottom = 6.dp)
                                .clickable { onImageTap(att.data) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    var active by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            active = (active + 1) % 3
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val scale by animateFloatAsState(
                targetValue = if (active == i) 1.3f else 1.0f,
                animationSpec = tween(300),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .scale(scale)
                    .background(Color(0xFF6B7280).copy(alpha = if (active == i) 1f else 0.4f), CircleShape)
            )
        }
    }
}
