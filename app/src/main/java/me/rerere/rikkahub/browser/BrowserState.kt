package me.rerere.rikkahub.browser

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLEncoder

@Serializable
data class BrowserTab(
    val id: String,
    val conversationId: String,
    val url: String = "about:blank",
    val title: String = "",
    val workspaceId: String? = null,
)

@Serializable
data class BrowserPage(val url: String, val title: String, val visitedAt: Long = System.currentTimeMillis())

@Serializable
data class BrowserDownload(val id: Long, val name: String, val url: String)

@Serializable
data class BrowserSettings(
    val searchEngine: String = "bing",
    val desktopMode: Boolean = false,
    val customUserAgent: String = "",
    val restoreTabs: Boolean = true,
    val saveHistory: Boolean = true,
)

@Serializable
data class BrowserState(
    val tabs: List<BrowserTab> = emptyList(),
    val selected: Map<String, String> = emptyMap(),
    val bookmarks: List<BrowserPage> = emptyList(),
    val history: List<BrowserPage> = emptyList(),
    val downloads: List<BrowserDownload> = emptyList(),
    val settings: BrowserSettings = BrowserSettings(),
) {
    fun tabsFor(conversationId: String) = tabs.filter { it.conversationId == conversationId }

    fun selectedTab(conversationId: String): BrowserTab? =
        tabsFor(conversationId).let { owned -> owned.find { it.id == selected[conversationId] } ?: owned.lastOrNull() }

    fun requireOwned(conversationId: String, tabId: String): BrowserTab =
        tabs.find { it.id == tabId && it.conversationId == conversationId }
            ?: error("tab_not_found: use list_tabs for this conversation")

    fun removeTab(tabId: String): BrowserState {
        val remaining = tabs.filterNot { it.id == tabId }
        return copy(
            tabs = remaining,
            selected = selected.mapNotNull { (owner, id) ->
                (if (id == tabId) remaining.lastOrNull { it.conversationId == owner }?.id else id)?.let { owner to it }
            }.toMap(),
        )
    }

    fun recordVisit(page: BrowserPage): BrowserState = if (settings.saveHistory && page.url.startsWith("http")) {
        copy(history = (listOf(page) + history.filterNot { it.url == page.url }).take(500))
    } else {
        this
    }

    fun forPersistence(): BrowserState = if (settings.restoreTabs) {
        this
    } else {
        copy(
            tabs = emptyList(),
            selected = emptyMap()
        )
    }
}

/** Address input accepts a search; tool navigation never turns invalid URLs into searches. */
fun browserAddress(value: String, searchEngine: String = "bing", allowSearch: Boolean = true): String {
    val input = value.trim()
    require(input.isNotEmpty()) { "请输入网址或搜索内容" }
    if (input == "about:blank") return input
    if (input.startsWith("rikkaplus://workspace/")) return input
    val explicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(input)
    val host = input.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostInput = !input.any(Char::isWhitespace) &&
        Regex("(?:localhost|[a-zA-Z0-9.-]+\\.[a-zA-Z0-9-]+|\\[[a-fA-F0-9:]+])(?::[0-9]+)?").matches(host)
    val candidate = if (hostInput) {
        (if (host.startsWith("localhost") || host.startsWith("127.0.0.1") ||
            host.startsWith("[::1]")) {
            "http://"
        } else {
            "https://"
        }) +
            input
    } else {
        input
    }
    val uri = runCatching { URI(candidate) }.getOrNull()
    if (uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null) {
        return uri.scheme.lowercase() + candidate.substring(uri.scheme.length)
    }
    require(allowSearch && !explicitScheme) { "仅支持 HTTP(S) 或工作区网页地址" }
    val query = URLEncoder.encode(input, "UTF-8")
    return when (searchEngine) {
        "google" -> "https://www.google.com/search?q=$query"
        "duckduckgo" -> "https://duckduckgo.com/?q=$query"
        "baidu" -> "https://www.baidu.com/s?wd=$query"
        else -> "https://www.bing.com/search?q=$query"
    }
}
