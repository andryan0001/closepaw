package ai.closepaw.gemini.live

import ai.closepaw.platform.AndroidPlatform
import com.google.gson.Gson
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveProtocolTest {

    private val gson = Gson()

    @Test
    fun `realtime audio input round-trips through json`() {
        val payload = BidiRealtimeInput(
            realtimeInput = RealtimeInputPayload(
                mediaChunks = listOf(
                    MediaChunk("audio/pcm;rate=16000", "dGVzdA=="),
                ),
            ),
        )
        val deserialized = gson.fromJson(gson.toJson(payload), BidiRealtimeInput::class.java)
        assertEquals("audio/pcm;rate=16000", deserialized.realtimeInput.mediaChunks[0].mimeType)
        assertEquals("dGVzdA==", deserialized.realtimeInput.mediaChunks[0].data)
    }

    @Test
    fun `realtime screen frame round-trips through json`() {
        val payload = BidiRealtimeInput(
            realtimeInput = RealtimeInputPayload(
                mediaChunks = listOf(MediaChunk("image/jpeg", "test_base64_data")),
            ),
        )
        val deserialized = gson.fromJson(gson.toJson(payload), BidiRealtimeInput::class.java)
        assertEquals("image/jpeg", deserialized.realtimeInput.mediaChunks[0].mimeType)
        assertEquals("test_base64_data", deserialized.realtimeInput.mediaChunks[0].data)
    }

    @Test
    fun `server toolCall with mobile_action deserializes`() {
        val json = """
            {
                "toolCall": {
                    "functionCalls": [
                        {
                            "name": "mobile_action",
                            "args": {
                                "action": "tap",
                                "x": 100,
                                "y": 200
                            },
                            "id": "call_123"
                        }
                    ]
                }
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, BidiServerMessage::class.java)
        assertNotNull(parsed.toolCall)
        assertEquals(1, parsed.toolCall?.functionCalls?.size)
        assertEquals("mobile_action", parsed.toolCall?.functionCalls?.get(0)?.name)
        assertEquals("call_123", parsed.toolCall?.functionCalls?.get(0)?.id)
    }

    @Test
    fun `server audio part deserializes`() {
        val json = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "aGVsbG8=" } }
                        ]
                    },
                    "turnComplete": false
                }
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, BidiServerMessage::class.java)
        val parts = parsed.serverContent?.modelTurn?.parts
        assertEquals(1, parts?.size)
        assertEquals("aGVsbG8=", parts?.get(0)?.inlineData?.data)
    }

    @Test
    fun `tool response serializes with function id and status`() {
        val response = BidiToolResponse(
            toolResponse = ToolResponsePayload(
                functionResponses = listOf(
                    FunctionResponse(
                        id = "call_123",
                        response = mapOf("status" to "success", "message" to "Action executed successfully"),
                    ),
                ),
            ),
        )
        val json = gson.toJson(response)
        assertTrue(json.contains("call_123"))
        assertTrue(json.contains("functionResponses"))
        assertTrue(json.contains("success"))
    }

    @Test
    fun `bridge rejects unknown function without touching platform`() = runBlocking {
        val platform = mockk<AndroidPlatform>(relaxed = true)
        val bridge = GeminiLiveActionBridge(platform)
        val outcome = bridge.execute("no_such_function", mapOf("action" to "tap"))
        assertEquals("error", outcome.status)
    }

    @Test
    fun `bridge rejects tap with missing coordinates`() = runBlocking {
        val platform = mockk<AndroidPlatform>(relaxed = true)
        val bridge = GeminiLiveActionBridge(platform)
        val outcome = bridge.execute(
            GeminiLiveClient.TOOL_MOBILE_ACTION,
            mapOf("action" to "tap"),
        )
        assertEquals("error", outcome.status)
        assertTrue(outcome.message.contains("x"))
    }

    @Test
    fun `bridge rejects unsupported live action`() = runBlocking {
        val platform = mockk<AndroidPlatform>(relaxed = true)
        val bridge = GeminiLiveActionBridge(platform)
        val outcome = bridge.execute(
            GeminiLiveClient.TOOL_MOBILE_ACTION,
            mapOf("action" to "pinch"),
        )
        assertEquals("error", outcome.status)
    }

    @Test
    fun `bridge rejects invalid key_event`() = runBlocking {
        val platform = mockk<AndroidPlatform>(relaxed = true)
        val bridge = GeminiLiveActionBridge(platform)
        val outcome = bridge.execute(
            GeminiLiveClient.TOOL_MOBILE_ACTION,
            mapOf("action" to "key_event", "key" to "VOLUME_UP"),
        )
        assertEquals("error", outcome.status)
    }
}
