package com.ahm.voicechat

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * WebSocket client for relay/server.js (pilot v1, untouched).
 *
 * Text messages are JSON. Binary messages are audio frames, forwarded
 * opaquely by the relay; this pilot uses raw PCM 16 kHz mono, NOT Opus.
 * Opus is a later optimisation and is deliberately not claimed here.
 *
 * Protocol used, exactly as in relay/server.js:
 *   out: hello {name}, request_code, mute {muted},
 *        group_create {name, password}, group_list,
 *        group_join {name, password}, group_leave
 *   in:  hello_ok, code {code}, verified {player}, mute_ok {muted},
 *        groups {groups}, group_ok {ok, group, reason},
 *        speaking {from, speaking} + one binary audio frame after it,
 *        pack_gone (the linked pack socket closed: player left the
 *        server while the game is still open).
 * Position messages are pack-side only. This app never sends a position.
 */
class RelayClient(private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onHelloOk(name: String, proximity: Int)
        fun onCode(code: String)
        fun onVerified(player: String)
        fun onLinked(player: String)
        fun onPackGone()
        fun onMuteOk(muted: Boolean)
        fun onGroups(groups: List<String>)
        fun onGroupOk(ok: Boolean, group: String?, reason: String?)
        fun onSpeaking(from: String)
        fun onAudio(bytes: ByteArray)
    }

    private val client = OkHttpClient()
    private var ws: WebSocket? = null
    var isConnected: Boolean = false
        private set

    fun connect(url: String, name: String, range: Int = 50) {
        disconnect()
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                listener.onConnected()
                sendJson(JSONObject().apply {
                    put("type", "hello")
                    put("name", name)
                    put("range", range)
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "hello_ok" -> listener.onHelloOk(
                            json.optString("name"),
                            json.optInt("proximity", 15)
                        )
                        "code" -> listener.onCode(json.optString("code"))
                        "verified" -> listener.onVerified(json.optString("player"))
                        "linked" -> listener.onLinked(json.optString("player"))
                        "pack_gone" -> listener.onPackGone()
                        "mute_ok" -> listener.onMuteOk(json.optBoolean("muted"))
                        "groups" -> {
                            val arr = json.optJSONArray("groups")
                            val out = mutableListOf<String>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) out.add(arr.optString(i))
                            }
                            listener.onGroups(out)
                        }
                        "group_ok" -> listener.onGroupOk(
                            json.optBoolean("ok"),
                            if (json.isNull("group")) null else json.optString("group"),
                            if (json.isNull("reason")) null else json.optString("reason")
                        )
                        "speaking" -> listener.onSpeaking(json.optString("from"))
                        else -> Unit // verify_ok and unknown types are ignored here.
                    }
                } catch (_: Exception) {
                    // Ignore one malformed text frame; do not drop the socket.
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onAudio(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                listener.onDisconnected()
            }
        })
    }

    fun disconnect() {
        try { ws?.close(1000, "bye") } catch (_: Exception) { }
        ws = null
        isConnected = false
    }

    private fun sendJson(json: JSONObject) {
        try { ws?.send(json.toString()) } catch (_: Exception) { }
    }

    fun sendAudio(bytes: ByteArray, length: Int) {
        try { ws?.send(bytes.toByteString(0, length)) } catch (_: Exception) { }
    }

    fun requestCode() = sendJson(JSONObject().apply { put("type", "request_code") })

    fun setRange(range: Int) = sendJson(JSONObject().apply {
        put("type", "range")
        put("range", range)
    })

    fun setMuted(muted: Boolean) = sendJson(JSONObject().apply {
        put("type", "mute")
        put("muted", muted)
    })

    fun groupCreate(name: String, password: String) = sendJson(JSONObject().apply {
        put("type", "group_create")
        put("name", name)
        put("password", password)
    })

    fun groupList() = sendJson(JSONObject().apply { put("type", "group_list") })

    fun groupJoin(name: String, password: String) = sendJson(JSONObject().apply {
        put("type", "group_join")
        put("name", name)
        put("password", password)
    })

    fun groupLeave() = sendJson(JSONObject().apply { put("type", "group_leave") })
}
