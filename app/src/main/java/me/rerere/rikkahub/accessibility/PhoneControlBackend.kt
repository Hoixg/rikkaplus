package me.rerere.rikkahub.accessibility

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.agent.*
import me.rerere.rikkahub.data.files.FilesManager

interface PhoneControlBackend {
    fun tools(): List<Tool>
    suspend fun status(): JsonObject
    suspend fun prepare(arguments: JsonObject): JsonObject = arguments
    suspend fun execute(tool: Tool, arguments: JsonObject): List<UIMessagePart> = tool.execute(arguments)
}

class AndroidPhoneControlBackend(
    private val context: Context,
    private val files: FilesManager,
    private val enabled: () -> Boolean,
) : PhoneControlBackend {
    override fun tools(): List<Tool> = listOf(
        readWindowTreeTool(), findNodeTool(), clickNodeTool(), setTextTool(), tapTool(), longPressTool(),
        swipeTool(), scrollTool(), globalActionTool(), takeScreenshotTool(files),
        launchAppTool(context), listInstalledAppsTool(context),
    )

    override suspend fun status(): JsonObject = withContext(Dispatchers.Main.immediate) {
        buildJsonObject {
            put("ai_enabled", enabled())
            put("permission_enabled", permissionEnabled(context))
            put("service_connected", PhoneAccessibilityService.instance != null)
            put("screen_on", context.getSystemService(PowerManager::class.java).isInteractive)
            put("locked", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            put("screenshot_supported", Build.VERSION.SDK_INT >= 30)
        }
    }

    override suspend fun prepare(arguments: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        val service = requireService()
        buildJsonObject {
            arguments.forEach { (key, value) -> if (key != "target_package") put(key, value) }
            val target = arguments["package_name"]?.jsonPrimitive?.contentOrNull
                ?: service.rootInActiveWindow?.let { root ->
                    try { root.packageName?.toString() } finally { recycle(root) }
                }
            target?.let { put("target_package", it) }
        }
    }

    override suspend fun execute(tool: Tool, arguments: JsonObject): List<UIMessagePart> = withContext(Dispatchers.Main.immediate) {
        requireService()
        val target = arguments["target_package"]?.jsonPrimitive?.contentOrNull
        if (target != null && target != context.packageName && tool.name != "launch_app") {
            val root = requireService().rootInActiveWindow
            val current = try { root?.packageName?.toString() } finally { root?.let(::recycle) }
            // The approval UI belongs to this app. Resume the app that was active before approval.
            if (current == context.packageName) {
                val launch = context.packageManager.getLaunchIntentForPackage(target)
                    ?: error("Return to $target before running this action, or turn off action approval for continuous control")
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                withTimeoutOrNull(2_500) {
                    while (true) {
                        val active = requireService().rootInActiveWindow
                        val pkg = try { active?.packageName?.toString() } finally { active?.let(::recycle) }
                        if (pkg == target) break
                        delay(100)
                    }
                } ?: error("Android did not bring $target to the foreground")
            }
        }
        val result = tool.execute(arguments)
        requireService()
        result
    }

    private fun requireService(): PhoneAccessibilityService {
        check(enabled()) { "AI phone control is disabled in Extensions > Phone Control" }
        val service = PhoneAccessibilityService.instance ?: error("Enable RikkaPlus in Android accessibility settings")
        check(context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "Unlock the phone before using phone control" }
        return service
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: android.view.accessibility.AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }

    companion object {
        fun permissionEnabled(context: Context): Boolean {
            val expected = ComponentName(context, PhoneAccessibilityService::class.java)
            val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return services.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
