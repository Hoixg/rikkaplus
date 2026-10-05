package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsSubagentsTest {
    @Test fun `legacy assistant stays disabled and enabling one does not enable others`() {
        assertFalse(JsonInstant.decodeFromString<Assistant>("{}").enableSubagents)
        val first = Assistant(enableSubagents = true)
        val second = Assistant()
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(Settings(assistants = listOf(first, second))))
        assertTrue(restored.assistants.single { it.id == first.id }.enableSubagents)
        assertFalse(restored.assistants.single { it.id == second.id }.enableSubagents)
    }

    @Test fun `old backup role fields are ignored and disappear from later backups`() {
        val assistant = Assistant(enableSubagents = true, systemPrompt = "keep instructions", workspaceId = Uuid.random())
        val settings = Settings(assistants = listOf(assistant), dynamicColor = false)
        val baseline = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject
        val legacyRoles = listOf(
            JsonArray(emptyList()),
            JsonInstant.parseToJsonElement("""[{"name":"explorer","systemPrompt":"old role","allowedTools":["read"]}]"""),
            JsonPrimitive("irrelevant old value"),
        )
        legacyRoles.forEach { roles ->
            val restored = JsonInstant.decodeFromString<Settings>(JsonObject(baseline + ("subagentPersonas" to roles)).toString())
            val saved = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(restored)).jsonObject
            assertEquals(baseline, saved)
            assertEquals(listOf(assistant), restored.assistants)
            assertFalse(saved.containsKey("subagentPersonas"))
        }
        assertFalse(JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings())).jsonObject.containsKey("subagentPersonas"))
    }

    @Test fun `saving settings clears legacy role preferences and preserves unrelated keys`() = runBlocking {
        val legacyKey = stringPreferencesKey("subagent_personas")
        val unrelatedKey = stringPreferencesKey("unrelated_extension")
        val state = MutableStateFlow<Preferences>(mutablePreferencesOf(legacyKey to "invalid obsolete roles", unrelatedKey to "keep"))
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(state.value).also { state.value = it }
        }
        val assistant = Assistant(enableSubagents = true, systemPrompt = "task delegation")
        SettingsStore.persistSettings(dataStore, Settings(assistants = listOf(assistant)))
        assertNull(state.value[legacyKey])
        assertEquals("keep", state.value[unrelatedKey])
        assertEquals(listOf(assistant), JsonInstant.decodeFromString<List<Assistant>>(state.value[SettingsStore.ASSISTANTS]!!))
    }

    @Test fun `conversation backup retains parent workspace and effective model without importing parent messages`() {
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random(), workspaceCwd = "subagents", modelOverrideId = Uuid.random())
        assertEquals(child, JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(child)))
        assertNull(JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(child).let {
            kotlinx.serialization.json.JsonObject(kotlinx.serialization.json.Json.parseToJsonElement(it).let { value ->
                (value as kotlinx.serialization.json.JsonObject).filterKeys { key -> key != "parentConversationId" }
            }).toString()
        }).parentConversationId)
    }
}
