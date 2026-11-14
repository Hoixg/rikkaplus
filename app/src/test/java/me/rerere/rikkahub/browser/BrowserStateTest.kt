package me.rerere.rikkahub.browser

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class BrowserStateTest {
    private val first = BrowserTab("a", "chat-one", "https://one.example")
    private val second = BrowserTab("b", "chat-two", "https://two.example")

    @Test fun `progress and unchanged metadata do not produce persistent state snapshots`() {
        val state = BrowserState(tabs = listOf(first, second))
        assertSame(state, state.pageChanged("a", null, null, completed = false))
        assertSame(state, state.pageChanged("a", first.url, first.title, completed = false))
        assertSame(state, state.pageChanged("closed", "https://late.example", "Late", completed = true))
    }

    @Test fun `only completed navigation records a visit and other tabs are retained`() {
        val state = BrowserState(tabs = listOf(first, second))
        val loading = state.pageChanged("a", "https://one.example/new", "Page", completed = false)
        assertTrue(loading.history.isEmpty())
        assertSame(second, loading.tabs[1])
        val finished = loading.pageChanged("a", null, null, completed = true)
        assertEquals("https://one.example/new", finished.history.single().url)
        assertEquals("Page", finished.history.single().title)
        assertEquals(1, finished.pageChanged("a", null, null, completed = true).history.size)
    }

    @Test fun `tools cannot address a tab belonging to another chat`() {
        val state = BrowserState(tabs = listOf(first, second), selected = mapOf("chat-one" to "b"))
        assertEquals(first, state.selectedTab("chat-one"))
        assertEquals(listOf(first), state.tabsFor("chat-one"))
        assertTrue(runCatching { state.requireOwned("chat-one", "b") }.isFailure)
        assertEquals(second, state.requireOwned("chat-two", "b"))
    }

    @Test fun `closing the last tab never selects another conversations tab`() {
        val closed = BrowserState(tabs = listOf(first, second), selected = mapOf("chat-one" to "a", "chat-two" to "b"))
            .removeTab("a")
        assertNull(closed.selectedTab("chat-one"))
        assertFalse(closed.selected.containsKey("chat-one"))
        assertEquals(second, closed.selectedTab("chat-two"))
    }

    @Test fun `closing selected tab falls back to another owned tab`() {
        val third = first.copy(id = "c")
        val state = BrowserState(tabs = listOf(first, second, third), selected = mapOf("chat-one" to "c"))
        assertEquals(first, state.removeTab("c").selectedTab("chat-one"))
    }

    @Test fun `saved tabs are not truncated to the live view budget`() {
        val tabs = (1..30).map { first.copy(id = "$it") }
        val state = BrowserState(tabs = tabs, selected = mapOf("chat-one" to "25"))
        val restored = JsonInstant.decodeFromString<BrowserState>(JsonInstant.encodeToString(state.forPersistence()))
        assertEquals(30, restored.tabs.size)
        assertEquals("25", restored.selectedTab("chat-one")?.id)
    }

    @Test fun `disabling restoration excludes tab records while preserving bookmarks and preferences`() {
        val bookmark = BrowserPage("https://example.com", "Saved")
        val state = BrowserState(tabs = listOf(first), selected = mapOf("chat-one" to "a"), bookmarks = listOf(bookmark),
            settings = BrowserSettings(restoreTabs = false))
        val restored = JsonInstant.decodeFromString<BrowserState>(JsonInstant.encodeToString(state.forPersistence()))
        assertTrue(restored.tabs.isEmpty())
        assertTrue(restored.selected.isEmpty())
        assertEquals(listOf(bookmark), restored.bookmarks)
        assertEquals(1, state.tabs.size)
    }

    @Test fun `history keeps latest visits within budget and respects its switch`() {
        var state = BrowserState()
        repeat(520) { state = state.recordVisit(BrowserPage("https://example.com/$it", "$it", it.toLong())) }
        assertEquals(500, state.history.size)
        state = state.recordVisit(BrowserPage("https://example.com/21", "updated"))
        assertEquals("updated", state.history.first().title)
        assertEquals(500, state.history.size)
        val disabled = state.copy(settings = state.settings.copy(saveHistory = false))
        assertEquals(disabled, disabled.recordVisit(BrowserPage("https://private.example", "private")))
        assertEquals(state, state.recordVisit(BrowserPage("about:blank", "blank")))
    }

    @Test fun `addresses support search and website hostnames with local development ports`() {
        assertEquals("https://example.com/path", browserAddress("example.com/path"))
        assertEquals("https://example.com:8080/path", browserAddress("example.com:8080/path"))
        assertEquals("http://localhost:8093/index.html", browserAddress("localhost:8093/index.html"))
        assertEquals("http://127.0.0.1:3000", browserAddress("127.0.0.1:3000"))
        assertEquals("https://example.com?q=search#result", browserAddress("example.com?q=search#result"))
        assertEquals("https://example.com/path", browserAddress("HTTPS://example.com/path", allowSearch = false))
        assertTrue(browserAddress("中文 搜索", "google").startsWith("https://www.google.com/search?q="))
        assertEquals("rikkaplus://workspace/site/index.html", browserAddress("rikkaplus://workspace/site/index.html", allowSearch = false))
    }

    @Test fun `navigation cannot execute a javascript address or expose private file schemes`() {
        listOf("javascript:alert(1)", "file:///data/data/private", "content://private/secret", "https://user:pass@example.com", "search words").forEach { input ->
            assertTrue(input, runCatching { browserAddress(input, allowSearch = false) }.isFailure)
        }
    }
}
