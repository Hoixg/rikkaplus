package me.rerere.rikkahub.browser

import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun engine(): BrowserEngine = BrowserEngine(context, "native-test", "workspace-native-test.rikkaplus.invalid",
        BrowserSettings(), context.assets.open("browser/async.js").bufferedReader().use { it.readText() },
        object : BrowserEngineEvents {
            override fun changed(view: BrowserViewState, url: String?, title: String?, completed: Boolean) = Unit
            override fun progress(value: Int) = Unit
            override fun download(url: String, name: String?, mime: String?) = Unit
            override fun chooser(request: BrowserFileChooser) { request.callback.onReceiveValue(null) }
            override fun dialog(request: BrowserDialog) { request.result.cancel() }
            override fun permission(request: BrowserPermission) { request.request.deny() }
            override fun popup(message: Message) = false
            override fun close() = Unit
            override fun resource(request: WebResourceRequest): WebResourceResponse? = null
            override fun gesture(value: JsonObject) = Unit
        })

    @Test fun defaultSettingsRetainTheRealUserAgentAndNormalViewport() = runBlocking {
        withContext(Dispatchers.Main) {
            val native = engine()
            try {
                val userAgent = native.webView.settings.userAgentString
                assertTrue(native.webView.settings.javaScriptEnabled)
                assertTrue(native.webView.settings.domStorageEnabled)
                assertTrue(native.webView.settings.useWideViewPort)
                assertTrue(native.webView.settings.loadWithOverviewMode)
                native.applySettings(BrowserSettings(saveHistory = false))
                assertEquals(userAgent, native.webView.settings.userAgentString)
            } finally { native.dispose() }
        }
    }

    @Test fun renderAndEmbeddedSrcdocUseTheSameNativeDocumentLifecycle() = runBlocking {
        withContext(Dispatchers.Main) {
            val native = engine()
            try {
                native.loadHtml("<title>Frame</title><iframe srcdoc='<p>frame-ready</p>'></iframe>")
                native.waitForDom()
                assertEquals("frame-ready", native.evaluate("""
                    const frame = document.querySelector('iframe');
                    if (frame.contentDocument.readyState !== 'complete') {
                        await new Promise(resolve => frame.addEventListener('load', resolve, {once:true}));
                    }
                    return frame.contentDocument.body.innerText.trim();
                """.trimIndent()).jsonPrimitive.content)
                native.loadHtml("<p>replacement</p>")
                native.waitForDom()
                assertEquals("replacement", native.evaluate("return document.body.innerText.trim();").jsonPrimitive.content)
            } finally { native.dispose() }
        }
    }
}
