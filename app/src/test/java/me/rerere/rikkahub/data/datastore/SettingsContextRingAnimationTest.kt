package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContextRingAnimationTest {
    @Test
    fun oldSettingsWithoutAnimationPreferenceDefaultToEnabled() {
        val settings = JsonInstant.decodeFromString<Settings>("""{"dynamicColor":false}""")
        assertTrue(settings.enableContextUsageRingAnimation)
        assertFalse(settings.dynamicColor)
    }

    @Test
    fun backupRoundTripKeepsDisabledAnimationAndOtherThemePreferences() {
        val settings = Settings(enableContextUsageRingAnimation = false, dynamicColor = false)
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(settings))
        assertFalse(restored.enableContextUsageRingAnimation)
        assertFalse(restored.dynamicColor)
    }
}
