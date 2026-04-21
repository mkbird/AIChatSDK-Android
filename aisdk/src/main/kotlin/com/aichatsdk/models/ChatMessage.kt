package com.aichatsdk.models

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class Role { USER, ASSISTANT }

/** Attachment kinds, from protocol v4.1+. */
enum class AttachmentKind { IMAGE, VIDEO, URL }

@JsonClass(generateAdapter = true)
data class ChatAttachment(
    val kind: String,            // "image" | "video" | "url"
    val data: String,            // base64 for image/video, URL string for url
    val mimeType: String? = null,
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val role: Role,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<ChatAttachment> = emptyList(),
) {
    val isUser: Boolean get() = role == Role.USER
    val hasImages: Boolean get() = attachments.any { it.kind == "image" }
}
