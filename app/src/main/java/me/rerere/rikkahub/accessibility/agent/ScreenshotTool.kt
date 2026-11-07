// Adapted from ExTV/rikkahub-agent, a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// Adaptation: use existing FilesManager attachments instead of cache/gallery copies.
package me.rerere.rikkahub.accessibility.agent

import android.graphics.Bitmap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.PhoneAccessibilityService
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

fun takeScreenshotTool(files: FilesManager): Tool = Tool(
    name = "take_screenshot",
    description = "Capture the current display as a vision attachment (Android 11+). Independent of the accessibility tree: animations and streaming text do not invalidate a screenshot. Coordinates use the original display pixels reported in width/height. Android can reject protected surfaces or rapid requests; rate_limited means wait before another screenshot.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("display_id", buildJsonObject { put("type", "integer"); put("description", "Display id, default 0") })
    }) },
    execute = { input ->
        val service = PhoneAccessibilityService.instance
        if (service == null) listOf(UIMessagePart.Text(AccessibilityServiceHandle.notActiveEnvelope().toString()))
        else when (val shot = service.captureScreenshot(input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull ?: 0)) {
            is PhoneAccessibilityService.ScreenshotOutcome.Failure -> listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "screenshot_unavailable"); put("reason", shot.reason)
            }.toString()))
            is PhoneAccessibilityService.ScreenshotOutcome.Success -> {
                try {
                    val width = shot.bitmap.width
                    val height = shot.bitmap.height
                    val bytes = withContext(Dispatchers.Default) {
                        ByteArrayOutputStream().use { output ->
                            check(shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Screenshot encoding failed" }
                            output.toByteArray()
                        }
                    }
                    val file = files.saveUploadFromBytes(bytes, "phone-${Uuid.random()}.png", "image/png")
                    listOf(UIMessagePart.Image(files.getFile(file).toUri().toString()), UIMessagePart.Text(buildJsonObject {
                        put("success", true); put("width", width); put("height", height)
                    }.toString()))
                } finally { shot.bitmap.recycle() }
            }
        }
    },
)
