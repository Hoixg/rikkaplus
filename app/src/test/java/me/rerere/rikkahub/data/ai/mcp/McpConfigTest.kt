package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpConfigTest {
    @Test
    fun `import supports both current transports and keeps standard HTTP imports compatible`() {
        val servers =
            parseMcpServersFromJson("""
            {"mcpServers": {
                "First": {"type":"sse","url":"https://example.com/sse","headers":{"X-Key":"secret"}},
                "Second": {"type":"http","url":"https://example.com/mcp","enabled":false},
                "Local": {"command":"npx","args":["server"]}
            }}
            """.trimIndent())
        assertEquals(listOf("sse", "streamable_http"), servers.map { it.transportType })
        assertEquals(listOf("X-Key" to "secret"), servers.first().commonOptions.headers)
        assertEquals(false, servers.last().commonOptions.enable)
    }

    @Test
    fun `partial connection edits preserve identity OAuth tool approvals and omitted headers`() {
        val options = McpCommonOptions(
            name = "Test",
            headers = listOf("Authorization" to "secret"),
            tools = listOf(McpTool(name = "remove", needsApproval = true)),
            oauth = McpOAuthState(enabled = true, accessToken = "token"),
        )
        val current = McpServerConfig.SseTransportServer(commonOptions = options, url = "https://example.com/sse")
        val result = configureMcpServer("Renamed", buildJsonObject { put("type", "streamable_http") }, current)
        assertEquals(current.id, result.id)
        assertEquals(current.serverUrl, result.serverUrl)
        assertEquals(options.copy(name = "Renamed"), result.commonOptions)
        assertEquals("streamable_http", result.transportType)
    }

    @Test
    fun `an explicit empty header map clears headers`() {
        val current = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(headers = listOf("X-Key" to "secret")),
            url = "https://example.com/mcp",
        )
        val result = configureMcpServer("Test", buildJsonObject { put("headers", buildJsonObject {}) }, current)
        assertEquals(emptyList<Pair<String, String>>(), result.commonOptions.headers)
    }

    @Test
    fun `invalid endpoints names and transports fail before writing a configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            configureMcpServer("Bad-Name", buildJsonObject { put("url", "https://example.com") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            configureMcpServer("Test", buildJsonObject { put("url", "file:///etc/config") })
        }
        assertThrows(IllegalStateException::class.java) {
            configureMcpServer("Test", buildJsonObject {
                put("url", "https://example.com")
                put("type", "stdio")
            })
        }
    }
}
