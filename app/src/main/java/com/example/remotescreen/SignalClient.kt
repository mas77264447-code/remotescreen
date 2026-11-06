package com.example.remotescreen

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// اتصال WebSocket بسيط بخادم الإشارات
class SignalClient(
    private val url: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onReady: () -> Unit,
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onReady()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    onMessage(JSONObject(text))
                } catch (_: Exception) { }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onMessage(JSONObject().put("type", "error").put("message", "انقطع الاتصال: ${t.message}"))
            }
        })
    }

    fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    fun close() {
        ws?.close(1000, null)
        ws = null
    }
}
