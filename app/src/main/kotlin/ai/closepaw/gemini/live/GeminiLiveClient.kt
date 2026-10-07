package ai.closepaw.gemini.live

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Persistent bidirectional WebSocket to Gemini Live (BidiGenerateContent).
 *
 * Lifecycle: [connect] → server opens → [GeminiLiveClient] sends the setup
 * handshake (model + AUDIO response modality + `mobile_action` tool).
 * Afterwards [sendAudioChunk]/[sendScreenFrame] stream realtime input and
 * [sendToolResponse] answers function calls. All server callbacks are
 * fanned out onto an internal dispatcher so socket threads never block.
 *
 * Thread-safe: OkHttp [WebSocket.send] may be called from any thread; the
 * socket reference is volatile and every send path null-checks it.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String = LiveModels.DEFAULT_MODEL_ID,
    private val gson: Gson = Gson(),
    private val onAudioOutputReceived: (base64Pcm: String) -> Unit = {},
    private val onTextReceived: (text: String) -> Unit = {},
    private val onToolCallReceived: (callId: String, functionName: String, args: Map<String, Any>) -> Unit = { _, _, _ -> },
    private val onStatusChanged: (status: LiveConnectionStatus) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    fun connect() {
        if (webSocket != null) return
        onStatusChanged(LiveConnectionStatus.Connecting)
        val url = "$BIDI_WEBSOCKET_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onStatusChanged(LiveConnectionStatus.Connected)
                ws.send(gson.toJson(buildSetupMessage()))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
                onStatusChanged(
                    LiveConnectionStatus.Error(t.localizedMessage ?: t.javaClass.simpleName),
                )
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                onStatusChanged(LiveConnectionStatus.Disconnected)
            }
        })
    }

    fun sendAudioChunk(base64Pcm: String) {
        val payload = BidiRealtimeInput(
            realtimeInput = RealtimeInputPayload(
                mediaChunks = listOf(
                    MediaChunk(mimeType = MIME_PCM_16K, data = base64Pcm),
                ),
            ),
        )
        webSocket?.send(gson.toJson(payload))
    }

    fun sendScreenFrame(base64Jpeg: String) {
        val payload = BidiRealtimeInput(
            realtimeInput = RealtimeInputPayload(
                mediaChunks = listOf(
                    MediaChunk(mimeType = MIME_JPEG, data = base64Jpeg),
                ),
            ),
        )
        webSocket?.send(gson.toJson(payload))
    }

    fun sendToolResponse(callId: String, status: String, message: String) {
        val response = BidiToolResponse(
            toolResponse = ToolResponsePayload(
                functionResponses = listOf(
                    FunctionResponse(
                        id = callId,
                        response = mapOf("status" to status, "message" to message),
                    ),
                ),
            ),
        )
        webSocket?.send(gson.toJson(response))
    }

    fun disconnect() {
        webSocket?.close(1000, "User ended session")
        webSocket = null
        onStatusChanged(LiveConnectionStatus.Disconnected)
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun buildSetupMessage(): BidiSetupMessage {
        val properties = mapOf(
            "action" to PropertySchema(
                type = "STRING",
                description = "Gesture to perform on the Android screen",
                enumValues = listOf("tap", "long_press", "swipe", "type_text", "key_event"),
            ),
            "x" to PropertySchema(type = "INTEGER", description = "X coordinate in screen pixels"),
            "y" to PropertySchema(type = "INTEGER", description = "Y coordinate in screen pixels"),
            "end_x" to PropertySchema(type = "INTEGER", description = "Ending X for swipe"),
            "end_y" to PropertySchema(type = "INTEGER", description = "Ending Y for swipe"),
            "text" to PropertySchema(type = "STRING", description = "Text to type into the focused input"),
            "key" to PropertySchema(
                type = "STRING",
                description = "System key: BACK, HOME or RECENTS",
                enumValues = listOf("BACK", "HOME", "RECENTS"),
            ),
        )
        val declaration = FunctionDeclaration(
            name = TOOL_MOBILE_ACTION,
            description = "Execute a touch or key gesture directly on the Android screen",
            parameters = FunctionParameters(
                properties = properties,
                required = listOf("action"),
            ),
        )
        return BidiSetupMessage(
            setup = SetupPayload(
                model = model,
                generationConfig = GenerationConfig(),
                tools = listOf(ToolDefinition(listOf(declaration))),
            ),
        )
    }

    private fun handleIncomingMessage(jsonText: String) {
        val serverMsg = try {
            gson.fromJson(jsonText, BidiServerMessage::class.java)
        } catch (_: Exception) {
            return
        }
        serverMsg.serverContent?.modelTurn?.parts?.forEach { part ->
            part.text?.takeIf { it.isNotEmpty() }?.let(onTextReceived)
            part.inlineData?.data?.takeIf { it.isNotEmpty() }?.let(onAudioOutputReceived)
        }
        serverMsg.toolCall?.functionCalls?.forEach { call ->
            onToolCallReceived(call.id, call.name, call.args ?: emptyMap())
        }
    }

    companion object {
        const val BIDI_WEBSOCKET_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        const val TOOL_MOBILE_ACTION = "mobile_action"
        const val MIME_PCM_16K = "audio/pcm;rate=16000"
        const val MIME_JPEG = "image/jpeg"
    }
}
