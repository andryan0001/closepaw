package ai.closepaw.gemini.live

import com.google.gson.annotations.SerializedName

/**
 * Gemini Live (BidiGenerateContent) WebSocket wire models.
 *
 * Client → server frames: [BidiSetupMessage] (handshake, sent first),
 * [BidiRealtimeInput] (mic PCM / screen JPEG chunks), [BidiToolResponse]
 * (function results). Server → client frames arrive as [BidiServerMessage]
 * carrying either model output ([ServerContent]) or tool calls
 * ([ToolCallPayload]).
 */

// ── Client → server ───────────────────────────────────────────────────

data class BidiSetupMessage(
    @SerializedName("setup") val setup: SetupPayload,
)

data class SetupPayload(
    @SerializedName("model") val model: String,
    @SerializedName("generationConfig") val generationConfig: GenerationConfig,
    @SerializedName("tools") val tools: List<ToolDefinition>,
)

data class GenerationConfig(
    @SerializedName("responseModalities") val responseModalities: List<String> = listOf("AUDIO"),
    @SerializedName("speechConfig") val speechConfig: SpeechConfig = SpeechConfig(),
)

data class SpeechConfig(
    @SerializedName("voiceConfig") val voiceConfig: VoiceConfig = VoiceConfig(),
)

data class VoiceConfig(
    @SerializedName("prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfig = PrebuiltVoiceConfig(),
)

data class PrebuiltVoiceConfig(
    @SerializedName("voiceName") val voiceName: String = "Puck",
)

data class ToolDefinition(
    @SerializedName("functionDeclarations") val functionDeclarations: List<FunctionDeclaration>,
)

data class FunctionDeclaration(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: FunctionParameters,
)

data class FunctionParameters(
    @SerializedName("type") val type: String = "OBJECT",
    @SerializedName("properties") val properties: Map<String, PropertySchema>,
    @SerializedName("required") val required: List<String>,
)

data class PropertySchema(
    @SerializedName("type") val type: String,
    @SerializedName("description") val description: String,
    @SerializedName("enum") val enumValues: List<String>? = null,
)

data class BidiRealtimeInput(
    @SerializedName("realtimeInput") val realtimeInput: RealtimeInputPayload,
)

data class RealtimeInputPayload(
    @SerializedName("mediaChunks") val mediaChunks: List<MediaChunk>,
)

data class MediaChunk(
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("data") val data: String,
)

data class BidiToolResponse(
    @SerializedName("toolResponse") val toolResponse: ToolResponsePayload,
)

data class ToolResponsePayload(
    @SerializedName("functionResponses") val functionResponses: List<FunctionResponse>,
)

data class FunctionResponse(
    @SerializedName("response") val response: Map<String, Any>,
    @SerializedName("id") val id: String,
)

// ── Server → client ─────────────────────────────────────────────────

data class BidiServerMessage(
    @SerializedName("serverContent") val serverContent: ServerContent? = null,
    @SerializedName("toolCall") val toolCall: ToolCallPayload? = null,
)

data class ServerContent(
    @SerializedName("modelTurn") val modelTurn: ModelTurn? = null,
    @SerializedName("turnComplete") val turnComplete: Boolean? = null,
)

data class ModelTurn(
    @SerializedName("parts") val parts: List<ModelPart>? = null,
)

data class ModelPart(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: InlineData? = null,
)

data class InlineData(
    @SerializedName("mimeType") val mimeType: String? = null,
    @SerializedName("data") val data: String? = null,
)

data class ToolCallPayload(
    @SerializedName("functionCalls") val functionCalls: List<FunctionCall>,
)

data class FunctionCall(
    @SerializedName("name") val name: String,
    @SerializedName("args") val args: Map<String, Any>? = null,
    @SerializedName("id") val id: String,
)
