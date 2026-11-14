package me.rerere.rikkahub.browser

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BrowserNavigationTest {
    @Test fun `late finish cannot acknowledge a reload before its start`() = runTest {
        val navigation = BrowserNavigation()
        navigation.begin()
        assertFalse(navigation.commit("https://site.example", "https://site.example"))
        assertTrue(navigation.pending)
        navigation.started()
        assertTrue(navigation.commit("https://site.example", "https://site.example"))
        navigation.document.await()
    }

    @Test fun `redirect completes with the actual current URL instead of the original request`() = runTest {
        val navigation = BrowserNavigation()
        navigation.begin()
        navigation.started()
        assertFalse(navigation.commit("https://site.example/login", "https://account.example/verify"))
        assertTrue(navigation.pending)
        assertTrue(navigation.commit("https://account.example/verify", "https://account.example/verify#widget"))
        navigation.document.await()
    }

    @Test fun `replacing an unfinished request fails its waiter and preserves the new one`() = runTest {
        val navigation = BrowserNavigation()
        navigation.begin()
        val previous = navigation.document
        navigation.begin()
        assertTrue(runCatching { previous.await() }.exceptionOrNull()?.message.orEmpty().contains("superseded"))
        assertTrue(navigation.pending)
        navigation.started()
        assertTrue(navigation.commit("https://new.example", "https://new.example"))
        navigation.document.await()
    }

    @Test fun `failed navigation cannot be revived by a late page finished callback`() = runTest {
        val navigation = BrowserNavigation()
        navigation.begin()
        navigation.started()
        navigation.fail("network failed")
        assertFalse(navigation.commit("https://site.example", "https://site.example"))
        assertEquals("network failed", runCatching { navigation.document.await() }.exceptionOrNull()?.message)
        navigation.begin()
        navigation.started()
        assertTrue(navigation.commit("https://site.example", "https://site.example"))
        navigation.document.await()
    }

    @Test fun `new page replaces an already committed document`() {
        val navigation = BrowserNavigation()
        val previous = navigation.document
        navigation.started()
        assertNotSame(previous, navigation.document)
        assertTrue(navigation.pending)
        assertFalse(navigation.commit(null, "https://site.example"))
    }

    @Test fun `verification frames and embedded documents are handled by the browser`() {
        listOf("https://challenges.cloudflare.com/path", "about:blank", "about:srcdoc", "about:srcdoc#frame",
            "blob:https://site.example/document", "data:text/html,content").forEach { url ->
            assertTrue(url, browserHandlesNavigation(url, mainFrame = true))
            assertTrue(url, browserHandlesNavigation(url, mainFrame = false))
        }
        assertFalse(browserHandlesNavigation("intent://open", mainFrame = true))
        assertFalse(browserHandlesNavigation("javascript:alert(1)", mainFrame = true))
    }
}
