package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledTaskScheduleDescriptionTest {
    @Test fun rendersExecutionModelWithProviderAndSupportsOlderSnapshots() {
        assertEquals("模型 A · 服务商 B", scheduledTaskModelDescription(buildJsonObject {
            put("model_name", "模型 A")
            put("model_provider", "服务商 B")
        }))
        assertEquals("模型 A", scheduledTaskModelDescription(buildJsonObject { put("model_name", "模型 A") }))
        assertEquals("跟随助手", scheduledTaskModelDescription(buildJsonObject { put("mode", "NEW_CHAT") }))
        assertEquals("跟随目标会话", scheduledTaskModelDescription(buildJsonObject { put("mode", "FOLLOW_UP") }))
        assertEquals("指定模型（名称未记录）", scheduledTaskModelDescription(buildJsonObject { put("model_override_id", "legacy-model") }))
    }

    @Test fun rendersEachScheduleInChinese() {
        assertEquals("仅执行一次：2026-10-09 10:00", scheduledTaskScheduleDescription(buildJsonObject {
            put("schedule_type", "ONCE")
            put("trigger_at", "2026-10-09 10:00")
        }))
        assertEquals("每天 09:30（从 2026-10-09 起，至 2026-10-30 止）",
            scheduledTaskScheduleDescription(buildJsonObject {
                put("schedule_type", "DAILY")
                put("time_of_day", "09:30")
                put("start_date", "2026-10-09")
                put("end_date", "2026-10-30")
            }))
        assertEquals("每周一、周五 08:00", scheduledTaskScheduleDescription(buildJsonObject {
            put("schedule_type", "WEEKLY")
            put("time_of_day", "08:00")
            put("weekdays", buildJsonArray { add(1); add(5) })
        }))
        assertEquals("每 45 分钟", scheduledTaskScheduleDescription(buildJsonObject {
            put("schedule_type", "INTERVAL")
            put("interval_minutes", 45)
        }))
    }
}
