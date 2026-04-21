package com.aichatsdk.services

import com.aichatsdk.models.AnyValue
import com.aichatsdk.models.ChatAttachment
import com.aichatsdk.models.MoshiBuilder
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/** REST fallback path. Mirrors iOS ChatRESTClient. */
class ChatRestClient(
    private val client: OkHttpClient = defaultClient,
) {
    @Throws(ChatRestException::class)
    suspend fun send(
        text: String,
        attachments: List<ChatAttachment>?,
        token: String?,
        baseURL: String,
        endpoint: String,
        context: Map<String, String>,
    ): String {
        if (token.isNullOrBlank()) throw ChatRestException("not_authenticated", "missing token")
        val url = baseURL.trimEnd('/') + endpoint
        val body = encodeBody(text, attachments, context)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ChatRestException("http_${resp.code}", resp.message)
            }
            val raw = resp.body?.string() ?: throw ChatRestException("empty_response", "")
            return decodeText(raw)
        }
    }

    private fun encodeBody(
        text: String,
        attachments: List<ChatAttachment>?,
        context: Map<String, String>,
    ): String {
        val buffer = Buffer()
        val w = JsonWriter.of(buffer)
        w.beginObject()
        context.forEach { (k, v) -> w.name(k).value(v) }
        w.name("text").value(text)
        if (!attachments.isNullOrEmpty()) {
            w.name("attachments")
            w.beginArray()
            attachments.forEach { att ->
                w.beginObject()
                w.name("kind").value(att.kind)
                w.name("data").value(att.data)
                att.mimeType?.let { w.name("mime_type").value(it) }
                w.endObject()
            }
            w.endArray()
        }
        w.endObject()
        return buffer.readUtf8()
    }

    private fun decodeText(json: String): String {
        val r = JsonReader.of(Buffer().writeUtf8(json))
        var text = ""
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "text" -> text = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return text
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class ChatRestException(val code: String, message: String) : RuntimeException("[$code] $message")
