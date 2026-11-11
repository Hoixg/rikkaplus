package me.rerere.rikkahub.browser

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

/** Browser metadata only. Cookies and website storage remain managed by WebView. */
internal class BrowserStore(directory: File) {
    private val file = AtomicFile(File(directory, "state.json"))
    private val writes = Mutex()

    suspend fun read(): BrowserState = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext BrowserState()
        JsonInstant.decodeFromString<BrowserState>(file.openRead().bufferedReader().use { it.readText() })
    }

    suspend fun write(state: BrowserState) = writes.withLock {
        withContext(Dispatchers.IO) {
            file.baseFile.parentFile?.mkdirs()
            val bytes = JsonInstant.encodeToString(state.forPersistence()).toByteArray()
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
    }
}
