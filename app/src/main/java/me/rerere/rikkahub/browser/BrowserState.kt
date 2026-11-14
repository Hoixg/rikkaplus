package me.rerere.rikkahub.browser

import kotlinx.coroutines.CompletableDeferred
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

    internal fun pageChanged(id: String, url: String?, title: String?, completed: Boolean): BrowserState {
        val old = tabs.find { it.id == id } ?: return this
        val page = old.copy(url = url ?: old.url, title = title ?: old.title)
        val next = if (old == page) this else copy(tabs = tabs.map { if (it.id == id) page else it })
        return if (completed) next.recordVisit(BrowserPage(page.url, page.title)) else next
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

/** Native commit acknowledges this navigation; DOM readiness is awaited once by BrowserEngine. */
internal class BrowserNavigation {
    var document = CompletableDeferred<Unit>().apply { complete(Unit) }
        private set
    private var expectingStart = false
    private var failed = false
    val pending get() = !document.isCompleted

    fun begin() {
        fail("browser_navigation_superseded")
        document = CompletableDeferred()
        expectingStart = true
        failed = false
    }

    fun started() {
        if (!expectingStart && document.isCompleted) begin()
        expectingStart = false
    }

    fun commit(callbackUrl: String?, currentUrl: String?): Boolean {
        if (expectingStart || failed || callbackUrl == null ||
            callbackUrl.substringBefore('#') != currentUrl?.substringBefore('#')) {
            return false
        }
        document.complete(Unit)
        return true
    }

    fun fail(reason: String) {
        failed = true
        document.completeExceptionally(IllegalStateException(reason))
    }
}

internal fun browserHandlesNavigation(url: String, mainFrame: Boolean): Boolean =
    !mainFrame || url.substringBefore(':').lowercase() in setOf("http", "https", "blob", "data") ||
        url.substringBefore('#') in setOf("about:blank", "about:srcdoc")

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
