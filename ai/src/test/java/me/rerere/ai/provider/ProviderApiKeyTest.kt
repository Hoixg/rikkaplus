package me.rerere.ai.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderApiKeyTest {
    private val first = ApiKeyEntry(id = "first", key = "first-secret", name = "主渠道")
    private val second = ApiKeyEntry(id = "second", key = "second-secret", name = "备用渠道", multiplier = 0.5f)

    @Test
    fun `existing upstream key strings normalize to stable unique entries`() {
        val provider = ProviderSetting.OpenAI(apiKey = " one, two\n one \t three ")
        assertEquals(listOf("one", "two", "three"), provider.apiKeyEntries().map { it.key })
        assertEquals(provider.apiKeyEntries(), provider.apiKeyEntries())
        assertFalse(provider.apiKeyEntries().any { it.id == it.key })
    }

    @Test
    fun `manual selection survives serialization for every provider type`() {
        listOf(ProviderSetting.OpenAI(), ProviderSetting.Google(), ProviderSetting.Claude()).forEach { provider ->
            val saved = provider.withApiKeyEntries(listOf(first, second), selectedId = second.id)
            val decoded = json.decodeFromString<ProviderSetting>(json.encodeToString(saved)) as ProviderSetting.ApiKey
            assertEquals("second-secret", decoded.selectedApiKey())
            assertEquals(listOf(first, second), decoded.apiKeyEntries())
            assertEquals("", decoded.apiKey)
            assertEquals("备用渠道 · 0.5×", decoded.apiKeyEntries()[1].displayLabel())
        }
    }

    @Test
    fun `unset selections always use the first key without rotation`() {
        val provider = ProviderSetting.OpenAI(apiKeyEntries = listOf(first, second))
        repeat(10) { assertEquals("first-secret", provider.selectedApiKey()) }
    }

    @Test
    fun `editing preserves selection and deletion never restores upstream keys`() {
        val provider = ProviderSetting.OpenAI(apiKey = "old-secret")
            .withApiKeyEntries(listOf(first, second), selectedId = second.id)
        val edited = provider.withApiKeyEntries(listOf(first, second.copy(key = "replacement"))) as ProviderSetting.ApiKey
        assertEquals(second.id, edited.selectedApiKeyId)
        assertEquals("replacement", edited.selectedApiKey())
        val remaining = edited.withApiKeyEntries(listOf(first)) as ProviderSetting.ApiKey
        assertEquals(first.id, remaining.selectedApiKeyId)
        val empty = remaining.withApiKeyEntries(emptyList())
        assertTrue(empty.apiKeyEntries().isEmpty())
        assertEquals("", empty.selectedApiKey())
    }

    @Test
    fun `key metadata is normalized without allowing invalid multipliers`() {
        val provider = ProviderSetting.Google().withApiKeyEntries(
            listOf(first.copy(name = "  primary  ", multiplier = Float.NaN), second.copy(key = " first-secret ")),
        )
        assertEquals(listOf(first.copy(name = "primary")), provider.apiKeyEntries())
    }

    @Test
    fun `account credentials stay separate from manual API keys`() {
        listOf(
            ProviderSetting.ChatGPT(credential = OAuthCredential(accessToken = "chatgpt-token")),
            ProviderSetting.Grok(credential = OAuthCredential(accessToken = "grok-token")),
        ).forEach { provider ->
            assertTrue(provider.apiKeyEntries().isEmpty())
            assertEquals("", provider.selectedApiKey())
            assertSame(provider, provider.withApiKeyEntries(listOf(first, second)))
        }
    }

    @Test
    fun `quota requests keep the manually selected key for every API key provider`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("{\"balance\":12}".toResponseBody()).build()
        }.build()
        val query = QuotaQuery(enabled = true, url = "{{baseUrl}}/credits?key={{apiKey}}", resultPath = "balance")
        val cases = listOf(
            ProviderSetting.OpenAI(quotaQuery = query) to "Authorization",
            ProviderSetting.Google(quotaQuery = query.copy(headers = listOf(CustomHeader("x-goog-api-key", "{{apiKey}}")))) to "x-goog-api-key",
            ProviderSetting.Claude(quotaQuery = query.copy(headers = listOf(CustomHeader("x-api-key", "{{apiKey}}")))) to "x-api-key",
        )
        cases.forEach { (setting, header) ->
            val selected = setting.withApiKeyEntries(listOf(first, second), selectedId = second.id)
            repeat(2) {
                val quota = when (selected) {
                    is ProviderSetting.OpenAI -> OpenAIProvider(client).getQuota(selected)
                    is ProviderSetting.Google -> GoogleProvider(client).getQuota(selected)
                    is ProviderSetting.Claude -> ClaudeProvider(client).getQuota(selected)
                    is ProviderSetting.Account -> error("Account providers do not use API keys")
                }
                assertEquals(ProviderQuota.Balance("%.2f".format(12f)), quota)
                assertEquals("second-secret", requests.last().url.queryParameter("key"))
                assertEquals(if (header == "Authorization") "Bearer second-secret" else "second-secret", requests.last().header(header))
            }
        }
        assertEquals(6, requests.size)
    }

    @Test
    fun `all text protocols send the selected key in their actual HTTP requests`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401)
                .message("Test response").body("{}".toResponseBody()).build()
        }.build()
        val openAI = OpenAIProvider(client)
        val google = GoogleProvider(client)
        val claude = ClaudeProvider(client)
        val cases = listOf(
            ProviderSetting.OpenAI() to "Authorization",
            ProviderSetting.OpenAI(useResponseApi = true) to "Authorization",
            ProviderSetting.Google() to "x-goog-api-key",
            ProviderSetting.Google(useInteractionsApi = true) to "x-goog-api-key",
            ProviderSetting.Claude() to "x-api-key",
        )
        cases.forEach { (setting, header) ->
            val selected = setting.withApiKeyEntries(listOf(first, second), selectedId = second.id)
            val params = TextGenerationParams(model = Model(modelId = "test-model"))
            val messages = listOf(UIMessage.user("hello"))
            val previousCount = requests.size
            val result = runCatching {
                when (selected) {
                    is ProviderSetting.OpenAI -> openAI.generateText(selected, messages, params)
                    is ProviderSetting.Google -> google.generateText(selected, messages, params)
                    is ProviderSetting.Claude -> claude.generateText(selected, messages, params)
                    is ProviderSetting.Account -> error("Account providers do not use API keys")
                }
            }
            assertEquals("${setting::class.simpleName}: ${result.exceptionOrNull()}", previousCount + 1, requests.size)
            assertEquals(
                if (header == "Authorization") "Bearer second-secret" else "second-secret",
                requests.last().header(header),
            )
        }
    }
}
