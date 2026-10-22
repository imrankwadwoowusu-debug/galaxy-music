package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamAlignmentUpgradeTest {

    private fun calculateSeekPosition(
        currentPosition: Long,
        offsetMs: Long,
        smartSourceAlignment: Boolean,
        smartVersionAlignment: Boolean,
    ): Long {
        val effectiveOffset = if (smartSourceAlignment && smartVersionAlignment) offsetMs else 0L
        return (currentPosition + effectiveOffset).coerceAtLeast(0L)
    }

    @Test
    fun `positive offset applies compensated forward seek`() {
        val currentPos = 30000L
        val offsetMs = 350L // target event happens 350ms later
        val seekPos = calculateSeekPosition(
            currentPosition = currentPos,
            offsetMs = offsetMs,
            smartSourceAlignment = true,
            smartVersionAlignment = true,
        )
        assertEquals(30350L, seekPos)
    }

    @Test
    fun `negative offset applies compensated backward seek`() {
        val currentPos = 30000L
        val offsetMs = -200L // target event happens 200ms earlier
        val seekPos = calculateSeekPosition(
            currentPosition = currentPos,
            offsetMs = offsetMs,
            smartSourceAlignment = true,
            smartVersionAlignment = true,
        )
        assertEquals(29800L, seekPos)
    }

    @Test
    fun `negative seek near track start clamps to 0ms without stalling`() {
        val currentPos = 100L
        val offsetMs = -500L
        val seekPos = calculateSeekPosition(
            currentPosition = currentPos,
            offsetMs = offsetMs,
            smartSourceAlignment = true,
            smartVersionAlignment = true,
        )
        assertEquals(0L, seekPos)
    }

    @Test
    fun `alignment disabled when smartSourceAlignment is false`() {
        val currentPos = 15000L
        val offsetMs = 400L
        val seekPos = calculateSeekPosition(
            currentPosition = currentPos,
            offsetMs = offsetMs,
            smartSourceAlignment = false,
            smartVersionAlignment = true,
        )
        assertEquals(15000L, seekPos)
    }

    @Test
    fun `alignment disabled when smartVersionAlignment parent is false`() {
        val currentPos = 15000L
        val offsetMs = 400L
        val seekPos = calculateSeekPosition(
            currentPosition = currentPos,
            offsetMs = offsetMs,
            smartSourceAlignment = true,
            smartVersionAlignment = false,
        )
        assertEquals(15000L, seekPos)
    }

    @Test
    fun `zero offset or fallback leaves seek position unchanged`() {
        val currentPos = 12345L
        val offsetMs = 0L
        val seekPos = calculateSeekPosition(
            currentPosition = currentPos,
            offsetMs = offsetMs,
            smartSourceAlignment = true,
            smartVersionAlignment = true,
        )
        assertEquals(12345L, seekPos)
    }
}
