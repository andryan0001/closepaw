package ai.closepaw.llm

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/**
 * OkHttp interceptor that disguises outgoing requests as OpenCode web-client
 * traffic (derived from `jasonxu114514/opencode2api`).
 *
 * Every request carries the OpenCode client fingerprint so Zen/Go gateways
 * treat it as first-party traffic instead of a generic SDK/bot:
 * - `User-Agent: opencode/1.0.0`
 * - `x-opencode-client: web`
 * - `x-opencode-session` / `x-session-id` / `x-session-affinity`: stable per
 *   conversation/agent run (see [renewSession]).
 * - `x-opencode-project: closepaw-agent`
 * - `x-opencode-request`: fresh random UUID per HTTP invocation.
 * - `Authorization: Bearer <key>` only when a non-blank key is supplied, so
 *   the anonymous/free lane (no key) still flows through.
 *
 * The same header set is mirrored onto the OpenAI-Java SDK path via
 * [ChatCompletionClient.extraHeadersProvider] because the SDK builds its own
 * internal OkHttpClient and cannot take an external interceptor. Both paths
 * share the session via [OpenCodeSession] so the SDK and raw-OkHttp (this
 * interceptor, credential validation, model discovery) requests correlate to
 * the same conversation.
 */
class OpenCodeInterceptor(
    private val apiKeyProvider: () -> String?,
) : Interceptor {

    @Volatile
    private var currentSessionId: String = UUID.randomUUID().toString()

    /** Start a new conversation scope — subsequent requests use a fresh session UUID. */
    fun renewSession() {
        currentSessionId = UUID.randomUUID().toString()
        OpenCodeSession.sessionId = currentSessionId
    }

    /** Stable session UUID currently used by this interceptor. */
    fun sessionId(): String = currentSessionId

    /**
     * Disguise headers for one request (fresh `x-opencode-request` UUID on
     * every call). Excludes `Authorization` — the caller adds it when a key
     * is present so SDK and raw paths share one header source.
     */
    fun requestHeaders(): Map<String, String> = buildMap {
        put(HEADER_USER_AGENT, USER_AGENT)
        put(HEADER_OPENCODE_CLIENT, CLIENT_WEB)
        put(HEADER_OPENCODE_SESSION, currentSessionId)
        put(HEADER_SESSION_ID, currentSessionId)
        put(HEADER_SESSION_AFFINITY, currentSessionId)
        put(HEADER_OPENCODE_PROJECT, PROJECT_CLOSEPAW_AGENT)
        put(HEADER_OPENCODE_REQUEST, UUID.randomUUID().toString())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val apiKey = apiKeyProvider()?.trim()

        val requestBuilder = original.newBuilder()
            .header("User-Agent", USER_AGENT)
            .header(HEADER_OPENCODE_CLIENT, CLIENT_WEB)
            .header(HEADER_OPENCODE_SESSION, currentSessionId)
            .header(HEADER_SESSION_ID, currentSessionId)
            .header(HEADER_SESSION_AFFINITY, currentSessionId)
            .header(HEADER_OPENCODE_PROJECT, PROJECT_CLOSEPAW_AGENT)
            .header(HEADER_OPENCODE_REQUEST, UUID.randomUUID().toString())

        if (!apiKey.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return chain.proceed(requestBuilder.build())
    }

    companion object {
        const val USER_AGENT = "opencode/1.0.0"
        const val CLIENT_WEB = "web"
        const val PROJECT_CLOSEPAW_AGENT = "closepaw-agent"

        const val HEADER_USER_AGENT = "User-Agent"
        const val HEADER_OPENCODE_CLIENT = "x-opencode-client"
        const val HEADER_OPENCODE_SESSION = "x-opencode-session"
        const val HEADER_SESSION_ID = "x-session-id"
        const val HEADER_SESSION_AFFINITY = "x-session-affinity"
        const val HEADER_OPENCODE_PROJECT = "x-opencode-project"
        const val HEADER_OPENCODE_REQUEST = "x-opencode-request"

        /** Default tier — OpenCode Zen (OpenAI-compatible `/v1` surface). */
        const val BASE_URL_ZEN = "https://opencode.ai/zen/v1/"

        /** Alternative tier — OpenCode Go (subscription catalog). */
        const val BASE_URL_GO = "https://opencode.ai/zen/go/v1/"

        /** Default model when OpenCode is selected and no catalog entry matches. */
        const val DEFAULT_MODEL_ID = "muse-spark-1.3-contributor-free"

        /** Preset model ids offered in the Settings UI when OpenCode is selected. */
        val PRESET_MODEL_IDS: List<String> = listOf(
            "muse-spark-1.3-contributor-free",
            "deepseek-v4-flash-free",
            "claude-3-7-sonnet",
            "mimo-v2.5-free",
        )

        /** Key sent on the anonymous/free lane when the user stored no key. */
        const val ANONYMOUS_API_KEY = "public"
    }
}

/**
 * Process-wide OpenCode session scope.
 *
 * The OpenAI-Java SDK path ([ChatCompletionClient]) cannot take an OkHttp
 * interceptor, so it reads the same session UUID from here via its
 * `extraHeadersProvider`. [OpenCodeInterceptor.renewSession] writes through
 * to this holder so both paths stay correlated per conversation/agent run.
 */
object OpenCodeSession {
    @Volatile
    var sessionId: String = UUID.randomUUID().toString()
        internal set

    fun renew() {
        sessionId = UUID.randomUUID().toString()
    }

    /** Fresh per-request header map (new `x-opencode-request` UUID per call). */
    fun requestHeaders(): Map<String, String> = mapOf(
        OpenCodeInterceptor.HEADER_USER_AGENT to OpenCodeInterceptor.USER_AGENT,
        OpenCodeInterceptor.HEADER_OPENCODE_CLIENT to OpenCodeInterceptor.CLIENT_WEB,
        OpenCodeInterceptor.HEADER_OPENCODE_SESSION to sessionId,
        OpenCodeInterceptor.HEADER_SESSION_ID to sessionId,
        OpenCodeInterceptor.HEADER_SESSION_AFFINITY to sessionId,
        OpenCodeInterceptor.HEADER_OPENCODE_PROJECT to OpenCodeInterceptor.PROJECT_CLOSEPAW_AGENT,
        OpenCodeInterceptor.HEADER_OPENCODE_REQUEST to UUID.randomUUID().toString(),
    )
}
