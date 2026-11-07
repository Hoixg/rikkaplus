// Adapted from ExTV/rikkahub-agent, a88f5854. SPDX-License-Identifier: AGPL-3.0-only
package me.rerere.rikkahub.accessibility.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal fun execTool(tool: Tool, args: String): String = runBlocking {
    (tool.execute(Json.parseToJsonElement(args)).first() as UIMessagePart.Text).text
}
