package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopHorizontalScrollTest {

    @Test
    fun `fractions accumulate into one step`() {
        val wheel = WheelAccumulator()
        assertTrue(wheel.push(0.4).isEmpty())
        assertEquals(listOf(1), wheel.push(0.7))
    }

    @Test
    fun `a flick emits one step per whole unit`() {
        val wheel = WheelAccumulator()
        assertEquals(listOf(1, 1, 1), wheel.push(3.2))
        assertEquals(listOf(1), wheel.push(0.9))
    }

    @Test
    fun `the other direction is kept separate`() {
        val wheel = WheelAccumulator()
        assertTrue(wheel.push(-0.6).isEmpty())
        assertEquals(listOf(-1), wheel.push(-0.6))
    }
}
