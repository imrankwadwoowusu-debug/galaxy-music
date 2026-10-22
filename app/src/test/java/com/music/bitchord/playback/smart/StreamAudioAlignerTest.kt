package com.music.bitchord.playback.smart

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StreamAudioAlignerTest {

    @Before
    fun setUp() {
        StreamAudioAligner.clearCache()
    }

    @Test
    fun `aligns synthetic signals with positive latency offset (+350ms)`() {
        val sampleRate = 44100.0
        val durationSec = 15.0
        val totalSamples = (sampleRate * durationSec).toInt()

        val pulseTimes = doubleArrayOf(1.0, 1.8, 3.1, 4.7, 6.0, 7.9, 10.2, 12.1)
        fun isPulse(t: Double): Boolean = pulseTimes.any { pt -> t >= pt && t < pt + 0.05 }

        // Generate aperiodic beat pulses
        val baseSignal = FloatArray(totalSamples) { i ->
            val t = i / sampleRate
            if (isPulse(t)) sin(2.0 * PI * 440.0 * t).toFloat() else 0f
        }

        val offsetMs = 350L // target is shifted by +350ms
        val offsetSamples = ((offsetMs / 1000.0) * sampleRate).toInt()

        // Target signal delayed by 350ms
        val targetSignal = FloatArray(totalSamples) { i ->
            val srcIndex = i - offsetSamples
            if (srcIndex in baseSignal.indices) baseSignal[srcIndex] else 0f
        }

        val (measuredOffsetMs, confidence) = StreamAudioAligner.computeOffsetFromSignals(
            sourceSamples = baseSignal,
            sourceSampleRate = sampleRate,
            sourceStartSec = 0.0,
            targetSamples = targetSignal,
            targetSampleRate = sampleRate,
            targetStartSec = 0.0,
        )

        assertTrue("Confidence must exceed minimum threshold (got $confidence)", confidence >= 0.40)
        // HOP_MS is 10ms, allow ±15ms tolerance
        assertTrue(
            "Measured offset $measuredOffsetMs must be within 15ms of true offset 350ms",
            abs(measuredOffsetMs - 350L) <= 15L,
        )
    }

    @Test
    fun `aligns synthetic signals with negative latency offset (-200ms)`() {
        val sampleRate = 44100.0
        val durationSec = 15.0
        val totalSamples = (sampleRate * durationSec).toInt()

        val pulseTimes = doubleArrayOf(1.0, 1.8, 3.1, 4.7, 6.0, 7.9, 10.2, 12.1)
        fun isPulse(t: Double): Boolean = pulseTimes.any { pt -> t >= pt && t < pt + 0.05 }

        val baseSignal = FloatArray(totalSamples) { i ->
            val t = i / sampleRate
            if (isPulse(t)) sin(2.0 * PI * 880.0 * t).toFloat() else 0f
        }

        val offsetMs = -200L // target is ahead by 200ms (event happens earlier)
        val offsetSamples = ((offsetMs / 1000.0) * sampleRate).toInt()

        val targetSignal = FloatArray(totalSamples) { i ->
            val srcIndex = i - offsetSamples
            if (srcIndex in baseSignal.indices) baseSignal[srcIndex] else 0f
        }

        val (measuredOffsetMs, confidence) = StreamAudioAligner.computeOffsetFromSignals(
            sourceSamples = baseSignal,
            sourceSampleRate = sampleRate,
            sourceStartSec = 0.0,
            targetSamples = targetSignal,
            targetSampleRate = sampleRate,
            targetStartSec = 0.0,
        )

        assertTrue("Confidence must exceed minimum threshold (got $confidence)", confidence >= 0.40)
        assertTrue(
            "Measured offset $measuredOffsetMs must be within 15ms of true offset -200ms",
            abs(measuredOffsetMs - (-200L)) <= 15L,
        )
    }

    @Test
    fun `dynamic window selection chooses opening window then falls back around playback position`() {
        // When opening bytes available: [0.0 .. 15.0]
        val openingWindow = StreamAudioAligner.resolveWindow(hasOpening = true, currentPosMs = 45000L)
        assertEquals(0.0, openingWindow.first, 0.001)
        assertEquals(15.0, openingWindow.second, 0.001)

        // When opening bytes missing/evicted: [max(0.0, currentPos - 5.0) .. currentPos + 10.0]
        val midTrackWindow = StreamAudioAligner.resolveWindow(hasOpening = false, currentPosMs = 45000L)
        assertEquals(40.0, midTrackWindow.first, 0.001)
        assertEquals(55.0, midTrackWindow.second, 0.001)

        // Near beginning of track: clamp start to 0.0
        val earlyTrackWindow = StreamAudioAligner.resolveWindow(hasOpening = false, currentPosMs = 2000L)
        assertEquals(0.0, earlyTrackWindow.first, 0.001)
        assertEquals(12.0, earlyTrackWindow.second, 0.001)
    }

    @Test
    fun `confidence is trusted only at or above the floor`() {
        assertTrue(StreamAudioAligner.isConfident(StreamAudioAligner.MIN_CONFIDENCE))
        assertFalse(StreamAudioAligner.isConfident(0.39))
    }

    @Test
    fun `empty signals report no offset and no confidence`() {
        val (offset, confidence) = StreamAudioAligner.computeOffsetFromSignals(
            sourceSamples = FloatArray(0),
            sourceSampleRate = 44100.0,
            sourceStartSec = 0.0,
            targetSamples = FloatArray(0),
            targetSampleRate = 44100.0,
            targetStartSec = 0.0,
        )
        assertEquals(0L, offset)
        assertEquals(0.0, confidence, 0.0)
    }

    @Test
    fun `offset caching stores and retrieves computed offset by mediaId and streamUrl`() {
        val mediaId = "track_xyz"
        val streamUrl = "https://audio.example.com/stream_flac.m4a"

        assertNull(StreamAudioAligner.getCachedOffsetMs(mediaId, streamUrl))

        StreamAudioAligner.putCachedOffsetMs(mediaId, streamUrl, 320L)
        assertEquals(320L, StreamAudioAligner.getCachedOffsetMs(mediaId, streamUrl))

        // Different URL for same mediaId is not cached
        assertNull(StreamAudioAligner.getCachedOffsetMs(mediaId, "https://other.com/stream.m4a"))
    }

    @Test
    fun `cache entries are keyed by stream url, so a repeat upgrade reuses the measurement`() {
        val mediaId = "track_xyz"
        StreamAudioAligner.putCachedOffsetMs(mediaId, "https://cdn/stream.flac", 320L)

        // A second upgrade of the same track resolves the same stream URL, whatever
        // generation marker the cache URI carries.
        assertEquals(320L, StreamAudioAligner.getCachedOffsetMs(mediaId, "https://cdn/stream.flac"))
        assertNull(StreamAudioAligner.getCachedOffsetMs(mediaId, "bitchord://watch?v=abc&q=hifi-2"))
    }

    @Test
    fun `a slow alignment does not hold the swap, and its job keeps running`() = runBlocking {
        val never = CompletableDeferred<Long>()
        val started = System.currentTimeMillis()
        assertEquals(0L, awaitAlignmentOrZero(never, waitMs = 50L))
        assertTrue("waited ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 1000L)
        assertTrue("the job must survive the wait so it can still cache", never.isActive)
    }

    @Test
    fun `a finished alignment is returned as-is`() = runBlocking {
        val done = CompletableDeferred(275L)
        assertEquals(275L, awaitAlignmentOrZero(done, waitMs = 50L))
        assertEquals(0L, awaitAlignmentOrZero(null, waitMs = 50L))
    }

    @Test
    fun `high-resolution 2ms hop resolves sub-10ms offset (+23ms within 3ms)`() {
        assertEquals(2.0, StreamAudioAligner.HOP_MS, 0.001)
        assertEquals(10.0, StreamAudioAligner.WIN_MS, 0.001)

        val sampleRate = 44100.0
        val durationSec = 10.0
        val totalSamples = (sampleRate * durationSec).toInt()
        val pulseTimes = doubleArrayOf(1.0, 2.3, 3.7, 5.1, 6.8, 8.2)
        fun isPulse(t: Double): Boolean = pulseTimes.any { pt -> t >= pt && t < pt + 0.03 }

        val baseSignal = FloatArray(totalSamples) { i ->
            val t = i / sampleRate
            if (isPulse(t)) sin(2.0 * PI * 660.0 * t).toFloat() else 0f
        }
        val offsetMs = 23L
        val offsetSamples = ((offsetMs / 1000.0) * sampleRate).toInt()
        val targetSignal = FloatArray(totalSamples) { i ->
            val srcIndex = i - offsetSamples
            if (srcIndex in baseSignal.indices) baseSignal[srcIndex] else 0f
        }

        val (measuredOffsetMs, confidence) = StreamAudioAligner.computeOffsetFromSignals(
            sourceSamples = baseSignal,
            sourceSampleRate = sampleRate,
            sourceStartSec = 0.0,
            targetSamples = targetSignal,
            targetSampleRate = sampleRate,
            targetStartSec = 0.0,
        )

        assertTrue("Confidence must exceed 0.40 (got $confidence)", confidence >= 0.40)
        assertTrue(
            "Expected +23ms ± 3ms with 2ms hop, got ${measuredOffsetMs}ms",
            abs(measuredOffsetMs - 23L) <= 3L,
        )
    }
}
