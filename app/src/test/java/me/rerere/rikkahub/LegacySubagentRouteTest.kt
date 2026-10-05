package me.rerere.rikkahub

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LegacySubagentRouteTest {
    @Test
    fun restoresOldPersonaRouteAndRedirectsOnlyThatEntryToSettings() {
        val oldPersonaRouteSerializer = Screen.SubagentPersonas.serializer()
        assertEquals(
            "me.rerere.rikkahub.Screen.SubagentPersonas",
            oldPersonaRouteSerializer.descriptor.serialName,
        )

        // This is the serialized payload for the old @Serializable data object route.
        val restoredPersonaRoute = Json.decodeFromString(oldPersonaRouteSerializer, "{}")
        assertSame(Screen.SubagentPersonas, restoredPersonaRoute)

        val backStack = mutableListOf<NavKey>(
            Screen.Chat("conversation-before"),
            Screen.SubagentPersonas,
            Screen.Setting,
        )

        redirectLegacySubagentPersonas(backStack)

        assertEquals(
            listOf(
                Screen.Chat("conversation-before"),
                Screen.Setting,
                Screen.Setting,
            ),
            backStack,
        )
    }
}
