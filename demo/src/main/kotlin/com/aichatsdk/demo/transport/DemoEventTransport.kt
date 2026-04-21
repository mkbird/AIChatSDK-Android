package com.aichatsdk.demo.transport

import android.util.Log
import com.aichatsdk.models.ChatEvent
import com.aichatsdk.models.ChatEventCodec
import com.aichatsdk.protocols.ChatEventTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * OkHttp WebSocket transport. URL pattern (matches iOS Demo):
 *   ws(s)://{host}/ws?token={t}&device_id=sdk-demo&family_id={f}
 *
 * Auto-reconnect with 1s backoff on closure or failure. Notifies via [onReconnect]
 * so the host (DemoApp) can re-issue session.init.
 */
class DemoEventTransport(
    private val baseURL: String,
    private val token: () -> String?,
    private val familyId: () -> String?,
    private val deviceId: String = "sdk-demo",
    private val pingIntervalSeconds: Long = 30,
) : ChatEventTransport {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected

    override var onEvent: ((ChatEvent) -> Unit)? = null
    override var onAudioData: ((ByteArray) -> Unit)? = null

    /** Optional callback fired after a reconnect succeeds. */
    var onReconnect: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    @Volatile private var manualClose = false

    fun connect() {
        manualClose = false
        openSocket(initial = true)
    }

    fun disconnect() {
        manualClose = true
        reconnectJob?.cancel()
        ws?.close(1000, "client closed")
        ws = null
        _isConnected.value = false
    }

    private fun openSocket(initial: Boolean) {
        val tk = token().orEmpty()
        val fam = familyId().orEmpty()
        val wsUrl = baseURL.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        val url = wsUrl.trimEnd('/') + "/ws?token=$tk&device_id=$deviceId&family_id=$fam"
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.value = true
                if (!initial) onReconnect?.invoke()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val ev = ChatEventCodec.fromJson(text)
                    DebugEventLog.add(DebugEventLog.Entry(System.currentTimeMillis(), DebugEventLog.Direction.Received, ev))
                    onEvent?.invoke(ev)
                } catch (t: Throwable) {
                    Log.e(TAG, "decode error: $text", t)
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                DebugEventLog.add(DebugEventLog.Entry(System.currentTimeMillis(), DebugEventLog.Direction.Received, null, isAudio = true, audioBytes = arr.size))
                onAudioData?.invoke(arr)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                webSocket.close(1000, null)
                if (!manualClose) scheduleReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                _isConnected.value = false
                if (!manualClose) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1000)
            if (!manualClose) openSocket(initial = false)
        }
    }

    override fun sendEvent(event: ChatEvent) {
        val json = ChatEventCodec.toJson(event)
        DebugEventLog.add(DebugEventLog.Entry(System.currentTimeMillis(), DebugEventLog.Direction.Sent, event))
        ws?.send(json)
    }

    override fun sendAudioData(data: ByteArray) {
        DebugEventLog.add(DebugEventLog.Entry(System.currentTimeMillis(), DebugEventLog.Direction.Sent, null, isAudio = true, audioBytes = data.size))
        ws?.send(ByteString.of(*data))
    }

    companion object { private const val TAG = "DemoEventTransport" }
}
