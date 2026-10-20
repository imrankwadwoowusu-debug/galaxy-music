package com.music.bitchord.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * The modal still owns touches while its content is sliding away. Catch a
 * press on the mini player here, before the fading controls can handle it.
 * Reverse on down: waiting for up could let the close finish and dispose the
 * gesture first. Other parts of the page keep their normal modal behaviour.
 * Applied to the stationary, full-screen root, not the translated content.
 */
@Composable
internal fun Modifier.reopenClosingPlayer(motion: PlayerSheetMotion, dock: PlayerDock): Modifier {
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { placed[0] = it }
        .pointerInput(motion, dock) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!motion.closing) return@awaitEachGesture
                val coordinates = placed[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                if (!dock.containsMiniPlayer(coordinates.localToScreen(down.position))) {
                    return@awaitEachGesture
                }
                down.consume()
                motion.open()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
}
