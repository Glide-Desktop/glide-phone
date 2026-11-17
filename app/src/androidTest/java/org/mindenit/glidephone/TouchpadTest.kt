package org.mindenit.glidephone

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections

class TouchpadTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val out = object : TouchpadOutput {
        override fun click(button: String) { events += button }
        override fun move(dx: Float, dy: Float) { events += "move" }
        override fun scroll(dx: Float, dy: Float) { events += if (dy > 0) "scroll+" else "scroll-" }
    }

    private fun clearPrefs() = context.getSharedPreferences("mousepad", android.content.Context.MODE_PRIVATE).edit().clear().commit()

    @Before fun setUp() { clearPrefs(); compose.setContent { TouchpadSurface(out, Modifier.fillMaxSize()) {} } }
    @After fun tearDown() { clearPrefs() }

    private fun pad() = compose.onNodeWithContentDescription("Сенсорна панель")
    private fun settle() { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }

    @Test
    fun singleTapIsALeftClickOnceNoSecondTapFollows() {
        pad().performTouchInput { down(center); advanceEventTime(50); up() }
        settle()
        assertEquals(listOf("left"), events.toList())
    }

    @Test
    fun twoFingerTapIsARightClick() {
        pad().performTouchInput {
            down(0, center); down(1, center + Offset(80f, 0f))
            advanceEventTime(60)
            up(1); up(0)
        }
        settle()
        assertEquals(listOf("right"), events.toList())
    }

    @Test
    fun threeFingerTapIsAMiddleClick() {
        pad().performTouchInput {
            down(0, center); down(1, center + Offset(80f, 0f)); down(2, center + Offset(160f, 0f))
            advanceEventTime(60)
            up(2); up(1); up(0)
        }
        settle()
        assertEquals(listOf("middle"), events.toList())
    }

    @Test
    fun doubleTapWithoutMovingIsADoubleClick() {
        pad().performTouchInput {
            down(center); advanceEventTime(50); up()
            advanceEventTime(100)
            down(center); advanceEventTime(50); up()
        }
        settle()
        assertEquals(listOf("double"), events.toList())
    }

    @Test
    fun doubleTapAndDragHoldsUntilTheNextTap() {
        pad().performTouchInput {
            down(center); advanceEventTime(50); up()
            advanceEventTime(100)
            down(center)
            repeat(10) { advanceEventTime(16); moveBy(Offset(30f, 0f)) }
            up()
        }
        settle()
        assertEquals("singlehold", events.first())
        assertTrue(events.contains("move"))
        events.clear()
        pad().performTouchInput { down(center); advanceEventTime(50); up() }
        settle()
        assertEquals(listOf("singlerelease"), events.toList())
    }

    @Test
    fun twoFingersUpwardsScrollDown() {
        pad().performTouchInput {
            down(0, center); down(1, center + Offset(80f, 0f))
            repeat(10) { advanceEventTime(16); moveBy(0, Offset(0f, -20f)); moveBy(1, Offset(0f, -20f)) }
            up(1); up(0)
        }
        settle()
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it == "scroll+" })
    }
}
