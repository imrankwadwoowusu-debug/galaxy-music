package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * Two-finger horizontal scrolling, which otherwise never reaches the app.
 *
 * Linux reports it as extra mouse buttons. macOS and Windows report it as a
 * wheel event with Shift held. A trackpad sends a stream of fractions, so they
 * are gathered until they add up to one shelf step.
 */
internal object DesktopHorizontalScroll {

    /** -1 for a swipe left, +1 for a swipe right. */
    private val _ticks = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val ticks: SharedFlow<Int> = _ticks

    private val accumulator = WheelAccumulator()

    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                when (val mouse = event) {
                    is MouseWheelEvent -> onWheel(mouse)
                    is MouseEvent -> onButton(mouse)
                }
            }, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        }
    }

    private fun onButton(mouse: MouseEvent) {
        if (mouse.id != MouseEvent.MOUSE_PRESSED) return
        when (mouse.button) {
            HORIZONTAL_LEFT -> _ticks.tryEmit(-1)
            HORIZONTAL_RIGHT -> _ticks.tryEmit(1)
        }
    }

    private fun onWheel(wheel: MouseWheelEvent) {
        if (!wheel.isShiftDown) return
        accumulator.push(wheel.preciseWheelRotation).forEach { _ticks.tryEmit(it) }
    }

    /** X11 button 6, renumbered by the JDK. */
    private const val HORIZONTAL_LEFT = 4

    /** X11 button 7, likewise. */
    private const val HORIZONTAL_RIGHT = 5
}

/**
 * Turns a stream of fractional wheel deltas into whole steps.
 *
 * Positive rotation yields positive steps. The remainder is kept so a slow
 * trackpad swipe still lands on a step instead of being dropped.
 */
internal class WheelAccumulator(private val threshold: Double = 1.0) {

    private var pending = 0.0

    fun push(rotation: Double): List<Int> {
        if (rotation == 0.0 || threshold <= 0.0) return emptyList()
        pending += rotation
        val steps = (pending / threshold).toInt()
        if (steps == 0) return emptyList()
        pending -= steps * threshold
        val direction = if (steps > 0) 1 else -1
        return List(kotlin.math.abs(steps)) { direction }
    }
}
