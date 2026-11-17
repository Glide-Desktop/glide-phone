package org.mindenit.glidephone

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections

class DigitizerTest {
    @get:Rule val compose = createComposeRule()
    private data class Report(val active: Boolean?, val touching: Boolean?, val x: Int?, val pressure: Double?, val rubber: Boolean?)
    private val reports: MutableList<Report> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        compose.setContent {
            Column { DigitizerDetail(null) { a, t, x, _, p, r -> reports += Report(a, t, x, p, r) } }
        }
    }

    @Test
    fun aFingerOnlyMovesTheCursor() {
        compose.onNodeWithText("Зона для малювання").performTouchInput {
            down(center); moveBy(Offset(20f, 0f)); up()
        }
        compose.waitForIdle()
        val down = reports.first()
        assertEquals(Report(true, false, down.x, 0.0, false), down)
        assertTrue(reports.drop(1).dropLast(1).all { it.pressure == 0.0 })
        assertEquals(Report(false, false, null, null, null), reports.last())
    }
}
