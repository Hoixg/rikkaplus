package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.PhoneAction
import me.rerere.rikkahub.accessibility.PhoneActionKind
import me.rerere.rikkahub.accessibility.PhoneControlBackend
import me.rerere.rikkahub.accessibility.PhoneControlConfig
import me.rerere.rikkahub.accessibility.string

fun createPhoneControlTools(
    owner: String,
    backend: PhoneControlBackend,
    config: () -> PhoneControlConfig,
): List<Tool> {
    fun output(value: JsonObject) = listOf(UIMessagePart.Text(value.toString()))
    fun requireEnabled() = check(config().enabled) { "AI phone control is disabled in Extensions > Phone Control" }
    val status = Tool(
        name = "phone_status",
        description = "Check phone-control permission, live accessibility connection, lock state and screenshot support. Does not read screen content. User setup: Extensions > Phone Control; enable the Android accessibility service and AI phone control.",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { output(backend.status()) },
    )
    if (!config().enabled) return listOf(status)
    return listOf(
        status,
        Tool(
            name = "phone_screen",
            description = "Read the active Android screen after waiting for layout changes to settle. action=read returns visible nodes with IDs, parent IDs, bounds, hints, checked/selected/focused states and offered actions; keyboardVisible reports the detected keyboard. action=screenshot additionally returns an image (Android 11+). Screen width/height are absolute device pixels; screenshot images may be resized. Prefer actionable node IDs over coordinates. Use snapshotId as snapshot_id in phone_action. Snapshots belong to this conversation and expire in 5 minutes. Actions and app opening normally return a fresh screen in after: use after.snapshotId and its node IDs for the next action instead of reading again. Read explicitly for a screenshot or when read_screen_again=true. A new read replaces the prior snapshot. Password text is redacted. Requires user-enabled accessibility; no Shizuku required.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {
                field("action", "string", "read or screenshot; defaults to read", listOf("read", "screenshot"))
            }) },
            execute = { input ->
                requireEnabled()
                val action = input.jsonObject.string("action") ?: "read"
                require(action in setOf("read", "screenshot")) { "Unknown screen action" }
                backend.screen(owner, action == "screenshot")
            },
        ),
        Tool(
            name = "phone_action",
            description = "Operate Android using the latest phone_screen or after snapshot. Node actions: click, long_click, input (replaces text; empty clears), scroll_forward/backward, scroll_up/down/left/right and ime_enter (the focused field's keyboard Search/Send/Done action, Android 11+). Use directional scrolling and ime_enter only when listed in the node's actions. Input results include input_verified when the resulting field can be checked; input_mismatch means the requested text was not observed and no automatic retry occurred. Coordinate tap/swipe use absolute device pixels and require an unchanged layout. Navigation: back, home, recents, notifications, quick_settings. Always provide snapshot_id; node actions also need node_id. Each snapshot permits one attempted action. After approval, the captured app is resumed and the target revalidated; a moved node is relocated only when its identity is unique and unchanged. Changed windows or ambiguous targets are rejected. The result normally contains a fresh screen in after: use after.snapshotId and its node IDs next, without another read. settled=false means the UI kept changing until the wait timed out. tree_empty means controls were not exposed, not that the page is blank; a screenshot may be needed. Inspect the result before continuing; do not blindly repeat unsuccessful or outcome_unknown actions. Read again only if read_screen_again=true or an image is needed.",
            needsApproval = { config().approvalRequired },
            prepareArguments = { input ->
                requireEnabled()
                PhoneAction.parse(input.jsonObject)
                backend.prepareAction(owner, input.jsonObject)
            },
            parameters = { InputSchema.Obj(properties = buildJsonObject {
                field("action", "string", "Operation", PhoneActionKind.entries.map { it.key })
                field("snapshot_id", "string", "Latest snapshotId from phone_screen or after")
                field("node_id", "string", "Visible node id from that same snapshot")
                field("text", "string", "Replacement text for input")
                listOf("x", "y", "end_x", "end_y").forEach { field(it, "number", "Absolute device pixel coordinate") }
                field("duration_ms", "integer", "Swipe duration: 100-2000 ms; default 400")
            }, required = listOf("action", "snapshot_id")) },
            execute = { input ->
                requireEnabled()
                output(backend.action(owner, PhoneAction.parse(input.jsonObject)))
            },
        ),
        Tool(
            name = "phone_apps",
            description = "List launchable Android apps (optional query filters names/packages), or open an app by package_name. Only launcher-visible apps are listed, at most 100 per query. action=open requires the configured phone-action approval, waits for the UI to settle and normally returns a fresh screen in after. Use after.snapshotId and its node IDs directly in phone_action; read the screen only if read_screen_again=true or a screenshot is needed. Requires user-enabled phone control.",
            needsApproval = { it.jsonObject.string("action") == "open" && config().approvalRequired },
            parameters = { InputSchema.Obj(properties = buildJsonObject {
                field("action", "string", "list or open", listOf("list", "open"))
                field("query", "string", "Optional app-name/package filter")
                field("package_name", "string", "Package to open")
            }, required = listOf("action")) },
            execute = { input ->
                requireEnabled()
                val args = input.jsonObject
                val action = args.string("action") ?: error("action is required")
                require(action in setOf("list", "open")) { "Unknown application action" }
                if (action == "open") require(!args.string("package_name").isNullOrBlank()) { "package_name is required" }
                output(backend.apps(owner, action, args.string("package_name"), args.string("query")))
            },
        ),
    )
}

private fun JsonObjectBuilder.field(name: String, type: String, description: String, values: List<String>? = null) {
    put(name, buildJsonObject {
        put("type", type)
        put("description", description)
        if (values != null) put("enum", JsonArray(values.map(::JsonPrimitive)))
    })
}
