package ai.closepaw.gemini.live

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.impl.MobileActionTool
import ai.closepaw.tool.impl.SystemButtonTool
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes Gemini Live `mobile_action` calls on ClosePaw's action engine.
 *
 * Gemini's live vocabulary (`tap`, `long_press`, `swipe`, `type_text`,
 * `key_event`) is translated to first-party tool params:
 * - `tap {x, y}` → `mobile_action {action: click, x, y}`
 * - `long_press {x, y}` → `mobile_action {action: long_press, x, y}`
 * - `swipe {x, y, end_x, end_y}` → `mobile_action {action: swipe,
 *   start: [x, y], end: [end_x, end_y]}`
 * - `type_text {text, x?, y?}` → `mobile_action {action: type,
 *   input_text: text, x?, y?}` (no coordinates = focused field)
 * - `key_event {key: BACK|HOME|RECENTS}` → `system_button {button}`
 *
 * Params travel the same `validate → createInvocation → execute` path as
 * agent turns, so targeting rules and failure semantics are identical. The
 * returned [LiveActionOutcome] feeds straight into `sendToolResponse`.
 */
class GeminiLiveActionBridge(
    private val platform: AndroidPlatform,
    private val snapshotProvider: () -> ScreenSnapshot? = { null },
    private val appClassifier: AppClassifier? = null,
    private val isCancelled: () -> Boolean = { false },
) {
    suspend fun execute(functionName: String, args: Map<String, Any>): LiveActionOutcome {
        if (functionName != GeminiLiveClient.TOOL_MOBILE_ACTION) {
            return LiveActionOutcome("error", "Unknown function: $functionName")
        }
        return try {
            when (liveActionOf(args)) {
                "tap" -> executeMobileAction(
                    JSONObject()
                        .put("action", "click")
                        .put("x", args.intOrThrow("x"))
                        .put("y", args.intOrThrow("y")),
                )
                "long_press" -> executeMobileAction(
                    JSONObject()
                        .put("action", "long_press")
                        .put("x", args.intOrThrow("x"))
                        .put("y", args.intOrThrow("y")),
                )
                "swipe" -> executeMobileAction(
                    JSONObject()
                        .put("action", "swipe")
                        .put("start", JSONArray(listOf(args.intOrThrow("x"), args.intOrThrow("y"))))
                        .put("end", JSONArray(listOf(args.intOrThrow("end_x"), args.intOrThrow("end_y")))),
                )
                "type_text" -> {
                    val text = (args["text"] as? String)?.trim().orEmpty()
                    if (text.isEmpty()) {
                        return LiveActionOutcome("error", "type_text requires non-empty text")
                    }
                    val params = JSONObject().put("action", "type").put("input_text", text)
                    args.intOrNull("x")?.let { params.put("x", it) }
                    args.intOrNull("y")?.let { params.put("y", it) }
                    executeMobileAction(params)
                }
                "key_event" -> runSystemButton(args)
                else -> LiveActionOutcome(
                    "error",
                    "Unsupported live action '${liveActionOf(args)}'. " +
                        "Valid: tap, long_press, swipe, type_text, key_event",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Live action failed", e)
            LiveActionOutcome("error", e.message ?: e.javaClass.simpleName)
        }
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun liveActionOf(args: Map<String, Any>): String =
        (args["action"] as? String)?.trim()?.lowercase().orEmpty()

    private suspend fun executeMobileAction(params: JSONObject): LiveActionOutcome {
        val tool = MobileActionTool()
        val validation = tool.validate(params)
        if (validation is ValidationResult.Invalid) {
            return LiveActionOutcome("error", validation.errors.joinToString("; "))
        }
        return try {
            val invocation = tool.createInvocation(params)
            when (val result = invocation.execute(liveContext())) {
                is ToolExecutionResult.Success ->
                    LiveActionOutcome("success", "Action executed successfully: ${result.output}")
                is ToolExecutionResult.Failure ->
                    LiveActionOutcome("error", result.error)
                is ToolExecutionResult.Cancelled ->
                    LiveActionOutcome("error", "Action cancelled: ${result.reason}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mobile action execution failed", e)
            LiveActionOutcome("error", e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun runSystemButton(args: Map<String, Any>): LiveActionOutcome {
        val key = ((args["key"] as? String)?.trim()?.lowercase().orEmpty())
        val button = when (key) {
            "back" -> "back"
            "home" -> "home"
            "recents" -> "recents"
            else -> return LiveActionOutcome(
                "error",
                "Unsupported key '$key'. Valid: BACK, HOME, RECENTS",
            )
        }
        val tool = SystemButtonTool()
        val params = JSONObject().put("button", button)
        if (tool.validate(params) is ValidationResult.Invalid) {
            return LiveActionOutcome("error", "Invalid system button: $button")
        }
        return try {
            val invocation = tool.createInvocation(params)
            when (val result = invocation.execute(liveContext())) {
                is ToolExecutionResult.Success ->
                    LiveActionOutcome("success", "Action executed successfully: ${result.output}")
                is ToolExecutionResult.Failure ->
                    LiveActionOutcome("error", result.error)
                is ToolExecutionResult.Cancelled ->
                    LiveActionOutcome("error", "Action cancelled: ${result.reason}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "System button execution failed", e)
            LiveActionOutcome("error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun liveContext(): ToolExecutionContext = object : ToolExecutionContext {
        override val platform: AndroidPlatform = this@GeminiLiveActionBridge.platform
        override val currentSnapshot: ScreenSnapshot? get() = snapshotProvider()
        override val appClassifier: AppClassifier? get() = this@GeminiLiveActionBridge.appClassifier
        override fun isCancelled(): Boolean = isCancelled.invoke()
    }

    private fun Map<String, Any>.intOrThrow(key: String): Int =
        intOrNull(key) ?: throw IllegalArgumentException("Missing or non-numeric '$key'")

    private fun Map<String, Any>.intOrNull(key: String): Int? =
        when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

    companion object {
        private const val TAG = "GeminiLiveActionBridge"
    }
}

/** Result reported back to Gemini via `toolResponse`. */
data class LiveActionOutcome(
    val status: String,
    val message: String,
)
