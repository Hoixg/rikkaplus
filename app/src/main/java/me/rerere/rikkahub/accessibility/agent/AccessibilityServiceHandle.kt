// Adapted from ExTV/rikkahub-agent, a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// Source and adaptation notes: docs/phone-control-sources.md
package me.rerere.rikkahub.accessibility.agent

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.accessibility.PhoneAccessibilityService

/**
 * Bridge between the LLM tool factories and the live AccessibilityService.
 * The service publishes itself via [PhoneAccessibilityService.instance]; this object
 * wraps the access pattern in a uniform "service-not-active" envelope so every tool
 * gets the same recovery hint.
 */
object AccessibilityServiceHandle {

    /** True iff our component is listed in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. */
    fun isEnabledInSettings(ctx: Context): Boolean {
        val expected = ComponentName(ctx, PhoneAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /** True iff the service has connected and the singleton is live. */
    fun isRunning(): Boolean = PhoneAccessibilityService.instance != null

    /**
     * Runs [block] with the live service if it's connected; otherwise returns the standard
     * service-not-active envelope as a JsonObject. Tools should wrap their entire execute()
     * body in this call.
     */
    suspend fun withService(
        block: suspend (PhoneAccessibilityService) -> JsonObject
    ): JsonObject {
        val svc = PhoneAccessibilityService.instance
            ?: return notActiveEnvelope()
        return block(svc)
    }

    fun notActiveEnvelope(): JsonObject = buildJsonObject {
        put("error", "AccessibilityService not active")
        put("recovery", "Enable RikkaPlus in Settings → Accessibility → Installed Apps")
    }
}
