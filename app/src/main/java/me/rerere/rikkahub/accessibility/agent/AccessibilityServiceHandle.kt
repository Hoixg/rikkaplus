// Adapted from ExTV/rikkahub-agent, a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// Source and adaptation notes: docs/phone-control-sources.md
package me.rerere.rikkahub.accessibility.agent

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
