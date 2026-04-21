package com.aichatsdk.services

import com.aichatsdk.models.ChatAttachment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChatRestClientTest {

    private lateinit var server: MockWebServer
    private val client = ChatRestClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `posts JSON with bearer token and parses text response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hello back"}"""))
        val reply = client.send(
            text = "ping",
            attachments = null,
            token = "abc",
            baseURL = server.url("/").toString().trimEnd('/'),
            endpoint = "/api/chat/message",
            context = mapOf("device_id" to "d1", "family_id" to "f1"),
        )
        assertThat(reply).isEqualTo("hello back")

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/api/chat/message")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer abc")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"text\":\"ping\"")
        assertThat(body).contains("\"device_id\":\"d1\"")
        assertThat(body).contains("\"family_id\":\"f1\"")
    }

    @Test fun `attachments included in body when present`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        client.send(
            text = "see",
            attachments = listOf(ChatAttachment("image", "AAAA", "image/jpeg")),
            token = "x",
            baseURL = server.url("/").toString().trimEnd('/'),
            endpoint = "/api/chat/message",
            context = emptyMap(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"attachments\":[")
        assertThat(body).contains("\"kind\":\"image\"")
        assertThat(body).contains("\"data\":\"AAAA\"")
        assertThat(body).contains("\"mime_type\":\"image/jpeg\"")
    }

    @Test fun `missing token throws not_authenticated`() = runTest {
        try {
            client.send("hi", null, null, server.url("/").toString(), "/x", emptyMap())
            fail("expected ChatRestException")
        } catch (e: ChatRestException) {
            assertThat(e.code).isEqualTo("not_authenticated")
        }
    }

    @Test fun `non-2xx maps to http_ code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        try {
            client.send("hi", null, "x", server.url("/").toString(), "/x", emptyMap())
            fail("expected exception")
        } catch (e: ChatRestException) {
            assertThat(e.code).isEqualTo("http_401")
        }
    }
}
