package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.model.MediaCreationRecord
import kotlin.uuid.Uuid

/** Wait for the service's terminal record, including output downloads, without returning progress to the model. */
internal suspend fun awaitMediaCreationResult(
    initial: MediaCreationRecord,
    observeRecord: (Uuid) -> Flow<MediaCreationRecord?>,
): MediaCreationRecord {
    if (!initial.status.isActive) return initial
    val result = requireNotNull(observeRecord(initial.id).first { it == null || !it.status.isActive }) {
        "媒体记录不存在或已删除；不会自动重新提交"
    }
    require(result.id == initial.id && result.sessionId == initial.sessionId) { "媒体记录不属于当前任务或聊天" }
    return result
}
