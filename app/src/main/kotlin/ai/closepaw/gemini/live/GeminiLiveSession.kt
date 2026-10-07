package ai.closepaw.gemini.live

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.AppClassifier
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates one Gemini Live co-pilot session.
 *
 * Wiring on [start]:
 * - [GeminiLiveClient] opens the BidiGenerateContent socket for [modelId].
 * - [GeminiAudioManager] streams mic PCM (16 kHz) up and plays model PCM
 *   (24 kHz) down.
 * - [GeminiScreenCaster] streams ~1 FPS JPEG frames from [mediaProjection].
 * - Incoming `mobile_action` calls run on [actionBridge] against [platform]
 *   and the result returns immediately as `toolResponse`.
 *
 * All callbacks hop onto the session [scope] (SupervisorJob + IO) so socket
 * threads stay unblocked and one failing child never kills the session.
 * [stop] is idempotent and releases mic, speaker, casting and the socket.
 */
class GeminiLiveSession(
    private val platform: AndroidPlatform,
    private val snapshotProvider: () -> ScreenSnapshot? = { null },
    private val appClassifier: AppClassifier? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<LiveSessionStatus>(LiveSessionStatus.Idle)
    val status: StateFlow<LiveSessionStatus> = _status.asStateFlow()

    private val _transcript = MutableStateFlow<List<String>>(emptyList())
    /** Rolling model text output (latest 50 turns), for UI display. */
    val transcript: StateFlow<List<String>> = _transcript.asStateFlow()

    private var client: GeminiLiveClient? = null
    private var audio: GeminiAudioManager? = null
    private var caster: GeminiScreenCaster? = null
    private var actionBridge: GeminiLiveActionBridge? = null

    @Volatile
    private var stopped = false

    /**
     * Start the co-pilot session. Requires a valid Gemini API key, a granted
     * [mediaProjection], and `RECORD_AUDIO` already granted. Safe to call
     * once — subsequent calls while running are ignored.
     */
    fun start(
        apiKey: String,
        modelId: String = LiveModels.DEFAULT_MODEL_ID,
        mediaProjection: MediaProjection,
        screenWidth: Int,
        screenHeight: Int,
        screenDensity: Int,
    ) {
        if (client != null || stopped) return
        require(apiKey.isNotBlank()) { "Gemini API key must not be blank" }

        val bridge = GeminiLiveActionBridge(
            platform = platform,
            snapshotProvider = snapshotProvider,
            appClassifier = appClassifier,
            isCancelled = { stopped },
        )
        actionBridge = bridge

        val audioManager = GeminiAudioManager(
            onAudioChunkReady = { base64Pcm -> client?.sendAudioChunk(base64Pcm) },
        )
        audio = audioManager

        val liveClient = GeminiLiveClient(
            apiKey = apiKey,
            model = modelId,
            onAudioOutputReceived = { base64Pcm -> audioManager.enqueuePlayback(base64Pcm) },
            onTextReceived = { text -> appendTranscript(text) },
            onToolCallReceived = { callId, functionName, args ->
                scope.launch { handleToolCall(callId, functionName, args) }
            },
            onStatusChanged = { connection ->
                _status.value = when (connection) {
                    LiveConnectionStatus.Idle -> LiveSessionStatus.Idle
                    LiveConnectionStatus.Connecting -> LiveSessionStatus.Connecting
                    LiveConnectionStatus.Connected -> LiveSessionStatus.Live
                    LiveConnectionStatus.Disconnected -> LiveSessionStatus.Ended
                    is LiveConnectionStatus.Error ->
                        LiveSessionStatus.Failed(connection.message)
                }
            },
        )
        client = liveClient

        val screenCaster = GeminiScreenCaster(
            mediaProjection = mediaProjection,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenDensity = screenDensity,
            onFrameCaptured = { base64Jpeg -> liveClient.sendScreenFrame(base64Jpeg) },
        )
        caster = screenCaster

        _status.value = LiveSessionStatus.Connecting
        liveClient.connect()
        audioManager.startRecording(scope)
        audioManager.startPlayback(scope)
        screenCaster.startCasting(scope)
    }

    /** End the session and release mic, speaker, casting and the socket. */
    fun stop() {
        if (stopped && client == null) return
        stopped = true
        try {
            caster?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Screen caster stop failed: ${e.message}")
        } finally {
            caster = null
        }
        try {
            audio?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Audio stop failed: ${e.message}")
        } finally {
            audio = null
        }
        try {
            client?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Client disconnect failed: ${e.message}")
        } finally {
            client = null
        }
        actionBridge = null
        _status.value = LiveSessionStatus.Ended
        scope.cancel()
    }

    // ── Private ───────────────────────────────────────────────────────

    private suspend fun handleToolCall(callId: String, functionName: String, args: Map<String, Any>) {
        val outcome = try {
            actionBridge?.execute(functionName, args)
                ?: LiveActionOutcome("error", "Session is stopping")
        } catch (e: Exception) {
            Log.w(TAG, "Tool call $callId failed", e)
            LiveActionOutcome("error", e.message ?: e.javaClass.simpleName)
        }
        client?.sendToolResponse(callId, outcome.status, outcome.message)
    }

    private fun appendTranscript(text: String) {
        _transcript.value = (_transcript.value + text).takeLast(MAX_TRANSCRIPT_TURNS)
    }

    companion object {
        private const val TAG = "GeminiLiveSession"
        private const val MAX_TRANSCRIPT_TURNS = 50
    }
}

/** UI-facing lifecycle of a co-pilot session. */
sealed interface LiveSessionStatus {
    data object Idle : LiveSessionStatus
    data object Connecting : LiveSessionStatus
    data object Live : LiveSessionStatus
    data object Ended : LiveSessionStatus
    data class Failed(val message: String) : LiveSessionStatus
}
