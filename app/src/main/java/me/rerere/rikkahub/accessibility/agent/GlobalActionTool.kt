// Adapted from ExTV/rikkahub-agent, a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// Source and adaptation notes: docs/phone-control-sources.md
package me.rerere.rikkahub.accessibility.agent

import android.accessibilityservice.AccessibilityService
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.ActionLogEntry

private val ACTION_MAP: Map<String, Int> = mapOf(
    "back" to AccessibilityService.GLOBAL_ACTION_BACK,
    "home" to AccessibilityService.GLOBAL_ACTION_HOME,
    "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
    "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
    "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
    "lock_screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
    "power_dialog" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
)

fun globalActionTool(): Tool = Tool(
    name = "global_action",
    description = """
        Perform an Android system action: back / home / recents / notifications / quick_settings /
        lock_screen / power_dialog. success only means the OS accepted the action; verify the
        outcome via the returned "after" object (e.g. after home, confirm shade_open is absent
        and the launcher package is foreground before assuming you are on the home screen).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { ACTION_MAP.keys.forEach { add(it) } })
                    put("description", "Which system action to perform")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->

        val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        val code = action?.let { ACTION_MAP[it] }
        if (action == null || code == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "action must be one of ${ACTION_MAP.keys.toList()}")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val ok = svc.performGlobalAction(code)
                svc.appendLog(
                    ActionLogEntry(
                        type = "global_action",
                        paramsSummary = action,
                        success = ok,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "rejected_by_os")
                }
            }
        }

        listOf(UIMessagePart.Text(payload.toString()))
    }
)
