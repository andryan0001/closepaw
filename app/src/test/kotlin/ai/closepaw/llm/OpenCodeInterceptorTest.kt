package ai.closepaw.llm

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.FakeSharedPreferences
import android.content.Context
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCodeInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun clientWith(interceptor: OpenCodeInterceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    @Test
    fun `injects all disguise headers without auth when key is absent`() {
        val interceptor = OpenCodeInterceptor { null }
        val client = clientWith(interceptor)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/v1/models")).build()).execute().use {
            assertEquals(200, it.code)
        }

        val recorded = server.takeRequest()
        assertEquals("opencode/1.0.0", recorded.getHeader("User-Agent"))
        assertEquals("web", recorded.getHeader("x-opencode-client"))
        assertEquals("closepaw-agent", recorded.getHeader("x-opencode-project"))

        val session = recorded.getHeader("x-opencode-session")
        assertTrue(!session.isNullOrBlank())
        assertEquals(session, recorded.getHeader("x-session-id"))
        assertEquals(session, recorded.getHeader("x-session-affinity"))

        assertTrue(!recorded.getHeader("x-opencode-request").isNullOrBlank())
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `injects bearer auth when key is present`() {
        val interceptor = OpenCodeInterceptor { "sk-test-key" }
        val client = clientWith(interceptor)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/v1/models")).build()).execute().use {
            assertEquals(200, it.code)
        }

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `request id is unique per invocation but session is stable`() {
        val interceptor = OpenCodeInterceptor { null }
        val client = clientWith(interceptor)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }

        repeat(2) {
            client.newCall(Request.Builder().url(server.url("/v1/models")).build()).execute().close()
        }

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertNotEquals(
            first.getHeader("x-opencode-request"),
            second.getHeader("x-opencode-request"),
        )
        assertEquals(
            first.getHeader("x-opencode-session"),
            second.getHeader("x-opencode-session"),
        )
    }

    @Test
    fun `renewSession rotates the session uuid`() {
        val interceptor = OpenCodeInterceptor { null }
        val before = interceptor.sessionId()
        interceptor.renewSession()
        assertNotEquals(before, interceptor.sessionId())
    }

    @Test
    fun `factory builds ChatCompletionClient for OPENCODE without stored key`() = runBlocking {
        val catalog = ModelCatalog.fromJson(
            """
            {
              "muse-spark-1.3-contributor-free": {
                "display_name": "Muse Spark 1.3 (OpenCode)",
                "provider": "OPENCODE",
                "api": "chat",
                "model_id": "muse-spark-1.3-contributor-free"
              }
            }
            """.trimIndent(),
        )
        val context: Context = mockk(relaxed = true)
        val store = AuthStore(context, prefsProvider = { FakeSharedPreferences() })
        val factory = LLMClientFactory(catalog, store)

        // No key stored — must NOT throw; falls back to the anonymous lane.
        val client = factory.create("muse-spark-1.3-contributor-free")
        assertTrue(client is ChatCompletionClient)
    }

    @Test
    fun `factory builds ChatCompletionClient for OPENCODE with stored key`() = runBlocking {
        val catalog = ModelCatalog.fromJson(
            """
            {
              "muse-spark-1.3-contributor-free": {
                "display_name": "Muse Spark 1.3 (OpenCode)",
                "provider": "OPENCODE",
                "api": "chat",
                "model_id": "muse-spark-1.3-contributor-free"
              }
            }
            """.trimIndent(),
        )
        val context: Context = mockk(relaxed = true)
        val store = AuthStore(context, prefsProvider = { FakeSharedPreferences() })
        store.set(LLMProvider.OPENCODE, AuthCredential.ApiKey("sk-opencode-test"))
        val factory = LLMClientFactory(catalog, store)

        val client = factory.create("muse-spark-1.3-contributor-free")
        assertTrue(client is ChatCompletionClient)
    }

    @Test
    fun `catalog resolves OPENCODE default model with zen base url`() {
        val catalog = ModelCatalog.fromJson(
            """
            {
              "muse-spark-1.3-contributor-free": {
                "display_name": "Muse Spark 1.3 (OpenCode)",
                "provider": "OPENCODE",
                "api": "chat",
                "model_id": "muse-spark-1.3-contributor-free"
              }
            }
            """.trimIndent(),
        )
        val entry = catalog.resolve("muse-spark-1.3-contributor-free")
        assertEquals(LLMProvider.OPENCODE, entry.provider)
        assertEquals(ApiType.CHAT, entry.api)
        assertEquals("https://opencode.ai/zen/v1/", entry.effectiveBaseUrl)
        assertEquals("OpenCode", LLMProvider.OPENCODE.displayLabel)
    }
}
