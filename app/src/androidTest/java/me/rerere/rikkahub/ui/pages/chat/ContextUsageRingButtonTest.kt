package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.formatContextLength
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Device tests use a controlled animation clock; no model or conversation service is required. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ContextUsageRingButtonTest {
    private val motionScale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }

    @get:Rule
    val compose = createComposeRule(effectContext = motionScale)

    private var usedTokens by mutableIntStateOf(35)
    private var darkMode by mutableStateOf(true)
    private var conversationKey by mutableIntStateOf(0)
    private var showRing by mutableStateOf(true)
    private var enableAnimation by mutableStateOf(true)
    private lateinit var lifecycleOwner: RingLifecycleOwner

    private fun show(initialTokens: Int = 35, fontScale: Float = 1f) {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            usedTokens = initialTokens
            lifecycleOwner = RingLifecycleOwner()
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycleOwner,
                LocalDarkMode provides darkMode,
                LocalDensity provides Density(density, fontScale),
            ) {
                MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface)) {
                        if (showRing) {
                            key(conversationKey) {
                                ContextUsageRingButton(
                                    usedTokens = usedTokens,
                                    modelWindowTokens = 100,
                                    displayCapacityTokens = 100,
                                    thresholdPercent = 80,
                                    tokenLimit = null,
                                    enableAnimation = enableAnimation,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun pixels(): IntArray {
        val pixels = compose.onNode(hasClickAction()).captureToImage().toPixelMap()
        return IntArray(pixels.width * pixels.height) { index ->
            pixels[index % pixels.width, index / pixels.width].toArgb()
        }
    }

    private fun topArcColor(): Color {
        val pixels = compose.onNode(hasClickAction()).captureToImage().toPixelMap()
        // The 25dp canvas sits in a 40dp button; its stroke center is 11.25dp above center.
        return pixels[pixels.width / 2, (pixels.height * (0.5f - 11.25f / 40f)).toInt()]
    }

    private fun assertColorNear(expected: Color, actual: Color) {
        val tolerance = 0.06f
        assertTrue("Expected $expected, got $actual", abs(expected.red - actual.red) < tolerance &&
            abs(expected.green - actual.green) < tolerance && abs(expected.blue - actual.blue) < tolerance)
    }

    @Test
    fun initialValueIsImmediateAndFlowDoesNotChangePercentage() {
        show()
        compose.onNodeWithText("35").assertIsDisplayed()
        val first = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("35").assertIsDisplayed()
        assertFalse("Nonzero usage should move the gradient", first.contentEquals(pixels()))
    }

    @Test
    fun increasingAndDecreasingUsagePassThroughIntermediateValues() {
        show(initialTokens = 10)
        compose.runOnIdle { usedTokens = 70 }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("10").assertDoesNotExist()
        compose.onNodeWithText("70").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("70").assertIsDisplayed()
        compose.runOnIdle { usedTokens = 20 }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("70").assertDoesNotExist()
        compose.onNodeWithText("20").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("20").assertIsDisplayed()
    }

    @Test
    fun zeroAndStoppedPageAreStaticAndResumedPageFlows() {
        show(initialTokens = 0)
        val zero = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertArrayEquals(zero, pixels())
        compose.runOnIdle { usedTokens = 35 }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { usedTokens = 65 }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("65").assertIsDisplayed()
        val stopped = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertArrayEquals(stopped, pixels())
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeByFrame()
        val resumed = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse(resumed.contentEquals(pixels()))
        compose.runOnIdle { showRing = false }
        compose.onNodeWithText("65").assertDoesNotExist()
        compose.runOnIdle { showRing = true }
        compose.onNodeWithText("65").assertIsDisplayed()
    }

    @Test
    fun zeroMotionScaleUsesStaticGradientAndImmediateValueUpdates() {
        motionScale.scaleFactor = 0f
        show()
        val first = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertArrayEquals(first, pixels())
        compose.runOnIdle { usedTokens = 65 }
        // Compose processes target changes and the disabled animation on the next frames.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("65").assertIsDisplayed()
        val updated = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertArrayEquals(updated, pixels())
        compose.runOnIdle { motionScale.scaleFactor = 1f }
        compose.mainClock.advanceTimeByFrame()
        val resumed = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse(resumed.contentEquals(pixels()))
    }

    @Test
    fun preferenceStopsBothAnimationsAndCanEnableFlowAgain() {
        show()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            enableAnimation = false
            usedTokens = 65
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("65").assertIsDisplayed()
        val disabled = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertArrayEquals(disabled, pixels())
        compose.runOnIdle { enableAnimation = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("65").assertIsDisplayed()
        val enabled = pixels()
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse(enabled.contentEquals(pixels()))
    }

    @Test
    fun themeWarningAndOverCapacityRemainVisible() {
        enableAnimation = false
        show(initialTokens = 100)
        compose.onNodeWithText("100").assertIsDisplayed()
        assertColorNear(darkColorScheme().error, topArcColor())
        compose.runOnIdle {
            usedTokens = 150
            darkMode = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("100").assertIsDisplayed()
        assertColorNear(lightColorScheme().error, topArcColor())
        compose.runOnIdle { usedTokens = 35 }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("35").assertIsDisplayed()
        assertColorNear(Color(0xFFB95283), topArcColor())
        compose.runOnIdle { darkMode = true }
        compose.mainClock.advanceTimeByFrame()
        assertColorNear(Color(0xFFDF8CAB), topArcColor())
    }

    @Test
    fun conversationChangeShowsCurrentValueAndLargeFontKeepsDetailsClickable() {
        show(fontScale = 2f)
        compose.runOnIdle {
            usedTokens = 5
            conversationKey++
        }
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithText("5").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = context.getString(
            R.string.chat_context_usage_tooltip,
            formatContextLength(5),
            formatContextLength(100),
        )
        compose.onNodeWithText(expected).assertIsDisplayed()
    }

    private class RingLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
