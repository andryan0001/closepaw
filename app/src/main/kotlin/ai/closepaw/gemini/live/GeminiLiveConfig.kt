package ai.closepaw.gemini.live

/**
 * Shared constants and connection state for the Gemini Live co-pilot mode.
 */
object LiveModels {
    /** Default live model (standard latency). */
    const val DEFAULT_MODEL_ID = "models/gemini-3.8-live"

    /** Extended-thinking live model (deeper reasoning, higher latency). */
    const val EXTENDED_THINKING_MODEL_ID = "models/gemini-3.8-live-extended-thinking"

    /** Model ids offered in the Settings picker when Gemini Live is selected. */
    val PRESET_MODEL_IDS: List<String> = listOf(
        DEFAULT_MODEL_ID,
        EXTENDED_THINKING_MODEL_ID,
    )
}

/** Connection lifecycle state of a [GeminiLiveClient] socket. */
sealed interface LiveConnectionStatus {
    data object Idle : LiveConnectionStatus
    data object Connecting : LiveConnectionStatus
    data object Connected : LiveConnectionStatus
    data object Disconnected : LiveConnectionStatus
    data class Error(val message: String) : LiveConnectionStatus
}
