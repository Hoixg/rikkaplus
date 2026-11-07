package me.rerere.rikkahub.accessibility

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayOutputStream

interface PhoneControlBackend {
    suspend fun status(): JsonObject
    suspend fun prepareAction(owner: String, arguments: JsonObject): JsonObject = arguments
    suspend fun screen(owner: String, screenshot: Boolean): List<UIMessagePart>
    suspend fun action(owner: String, action: PhoneAction): JsonObject
    suspend fun apps(owner: String, action: String, packageName: String?, query: String?): JsonObject
}

class AndroidPhoneControlBackend(
    private val context: Context,
    private val files: FilesManager,
    private val enabled: () -> Boolean,
) : PhoneControlBackend {
    override suspend fun prepareAction(owner: String, arguments: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        val service = requireService()
        val action = PhoneAction.parse(arguments)
        val screen = service.snapshots.get(owner, action.snapshotId)
        action.validateBounds(screen)
        val node = if (action.kind.needsNode) screen.nodes.find { it.id == action.nodeId }
            ?: error("Unknown node_id; read the screen again") else null
        buildJsonObject {
            arguments.forEach { (key, value) -> if (key !in setOf("target_package", "target_label", "target_bounds")) put(key, value) }
            put("target_package", screen.packageName)
            if (node != null) {
                put("target_label", node.text.ifBlank { node.description }.ifBlank { node.hint }.take(160))
                put("target_bounds", JsonInstant.encodeToJsonElement(node.bounds))
            }
        }
    }

    override suspend fun status(): JsonObject = withContext(Dispatchers.Main.immediate) {
        val service = PhoneAccessibilityService.instance
        buildJsonObject {
            put("ai_enabled", enabled())
            put("permission_enabled", permissionEnabled(context))
            put("service_connected", service != null)
            put("screen_on", context.getSystemService(PowerManager::class.java).isInteractive)
            put("locked", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            put("screenshot_supported", Build.VERSION.SDK_INT >= 30)
            if (service != null && enabled()) {
                put("foreground_package", service.foregroundPackage())
            }
        }
    }

    override suspend fun screen(owner: String, screenshot: Boolean): List<UIMessagePart> = withContext(Dispatchers.Main.immediate) {
        val service = requireService()
        service.operations.withLock {
            val device = actionDevice(service)
            val settled = device.awaitQuiet()
            val before = device.capture()
            val image = if (screenshot) service.screenshot() else null
            try {
                val current = if (image != null) device.capture() else before
                check(before.sameLayout(current)) {
                    "Screen changed while reading; read the screen again"
                }
                service.snapshots.put(owner, current)
                val output = mutableListOf<UIMessagePart>(UIMessagePart.Text(buildJsonObject {
                    JsonInstant.encodeToJsonElement(current).jsonObject.forEach { (key, value) -> put(key, value) }
                    put("settled", settled)
                    if (current.nodes.isEmpty()) {
                        put("tree_empty", true)
                        put("observation_hint", "No readable controls are exposed. The page may be loading, canvas-based, or accessibility-restricted; request a screenshot if needed.")
                    }
                }.toString()))
                if (image != null) {
                    val bytes = withContext(Dispatchers.Default) {
                        val scale = (1440.0 / maxOf(image.width, image.height)).coerceAtMost(1.0)
                        val bitmap = if (scale < 1) Bitmap.createScaledBitmap(image,
                            (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), true) else image
                        try { ByteArrayOutputStream().use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Screenshot encoding failed" }
                            stream.toByteArray()
                        } } finally { if (bitmap !== image) bitmap.recycle() }
                    }
                    val saved = files.saveUploadFromBytes(bytes, "phone-${current.snapshotId}.png", "image/png")
                    output.add(UIMessagePart.Image(files.getFile(saved).toUri().toString()))
                    output.add(UIMessagePart.Text("Screenshot pixels may be resized; use the screen width/height and node bounds for actions."))
                }
                output
            } finally { image?.recycle() }
        }
    }

    override suspend fun action(owner: String, action: PhoneAction): JsonObject = withContext(Dispatchers.Main.immediate) {
        val service = requireService()
        service.operations.withLock {
            PhoneActionRunner(service.snapshots, actionDevice(service)).run(owner, action)
        }
    }

    override suspend fun apps(owner: String, action: String, packageName: String?, query: String?): JsonObject {
        requireService()
        return when (action) {
            "list" -> withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = context.packageManager.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                    .distinctBy { it.first }.sortedBy { it.second.lowercase() }
                    .filter { query.isNullOrBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
                buildJsonObject {
                    put("apps", JsonArray(apps.take(100).map { (pkg, name) -> buildJsonObject { put("package_name", pkg); put("name", name) } }))
                    put("truncated", apps.size > 100)
                }
            }
            "open" -> withContext(Dispatchers.Main.immediate) {
                require(!packageName.isNullOrBlank()) { "package_name is required" }
                val service = requireService()
                service.operations.withLock {
                    val version = service.changeVersion
                    openApp(packageName)
                    awaitPackage(service, packageName)
                    val observation = PhoneActionRunner(service.snapshots, actionDevice(service)).observe(owner, afterChange = version)
                    buildJsonObject {
                        put("opened", true)
                        put("package_name", packageName)
                        observation.forEach { (key, value) -> put(key, value) }
                    }
                }
            }
            else -> error("Unknown application action")
        }
    }

    private fun requireService(): PhoneAccessibilityService {
        check(enabled()) { "AI phone control is disabled in Extensions > Phone Control" }
        val service = PhoneAccessibilityService.instance ?: error("Enable RikkaPlus in Android accessibility settings")
        check(context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "Unlock the phone before using phone control" }
        return service
    }

    private fun requireService(service: PhoneAccessibilityService) {
        check(requireService() === service) { "Accessibility service reconnected; read the screen again" }
    }

    private fun actionDevice(service: PhoneAccessibilityService) = object : PhoneActionDevice {
        override suspend fun prepare(packageName: String) {
            requireService(service)
            restoreAfterApproval(service, packageName)
        }

        override suspend fun awaitQuiet(): Boolean {
            requireService(service)
            val quiet = service.awaitQuiet()
            requireService(service)
            return quiet
        }

        override fun changeVersion(): Long = service.changeVersion

        override suspend fun awaitAfter(since: Long): Boolean {
            requireService(service)
            val quiet = service.awaitAfter(since)
            requireService(service)
            return quiet
        }

        override suspend fun capture(): PhoneScreen = capturePhoneScreen {
            requireService(service)
            service.capture()
        }

        override suspend fun perform(action: PhoneAction, target: PhoneNode?, screen: PhoneScreen): Boolean {
            requireService(service)
            return when (action.kind) {
                PhoneActionKind.CLICK, PhoneActionKind.LONG_CLICK, PhoneActionKind.INPUT,
                PhoneActionKind.SCROLL_FORWARD, PhoneActionKind.SCROLL_BACKWARD ->
                    service.performNodeAction(checkNotNull(target), action, screen)
                PhoneActionKind.SCROLL_UP, PhoneActionKind.SCROLL_DOWN, PhoneActionKind.SCROLL_LEFT,
                PhoneActionKind.SCROLL_RIGHT, PhoneActionKind.IME_ENTER ->
                    service.performNodeAction(checkNotNull(target), action, screen)
                PhoneActionKind.TAP, PhoneActionKind.SWIPE -> service.gesture(action)
                else -> service.performGlobalAction(when (action.kind) {
                    PhoneActionKind.BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    PhoneActionKind.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    PhoneActionKind.RECENTS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                    PhoneActionKind.NOTIFICATIONS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    PhoneActionKind.QUICK_SETTINGS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    else -> error("Unknown navigation action")
                })
            }
        }
    }

    private suspend fun restoreAfterApproval(service: PhoneAccessibilityService, target: String) {
        val current = service.foregroundPackage()
        if (current == target) return
        check(current == context.packageName && target != context.packageName) { "Foreground app changed; read the screen again" }
        openApp(target)
        awaitPackage(service, target)
    }

    private fun openApp(packageName: String) {
        requireService()
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: error("No launcher found for $packageName")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private suspend fun awaitPackage(service: PhoneAccessibilityService, packageName: String) = withTimeout(4_000) {
        while (true) {
            requireService(service)
            val current = service.foregroundPackage()
            if (current == packageName) break
            delay(80)
        }
    }

    companion object {
        fun permissionEnabled(context: Context): Boolean {
            val expected = ComponentName(context, PhoneAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
