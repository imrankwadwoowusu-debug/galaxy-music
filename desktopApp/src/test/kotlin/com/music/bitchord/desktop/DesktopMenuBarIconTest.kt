package com.music.bitchord.desktop

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopMenuBarIconTest {

    @Test
    fun `menu bar glyph fills the frame instead of sitting in a tile`() {
        val svg = File("src/main/composeResources/drawable/logo_mark.svg").readBytes()
        val image = DesktopMenuBarIcon.glyph(svg, 36)
        val bounds = opaque(image)
        val heightFill = bounds.height.toDouble() / image.height
        val widthFill = bounds.width.toDouble() / image.width
        assertTrue(heightFill > 0.9, "glyph height fill $heightFill")
        assertTrue(widthFill > 0.9, "glyph width fill $widthFill")
        assertTrue(image.width > image.height, "the waveform is wider than it is tall")
    }

    @Test
    fun `dock artwork sits inside the macOS icon grid`() {
        val image = ImageIO.read(javaClass.getResource("/macos/dock.png"))
        val bounds = opaque(image)
        val inset = bounds.left.toDouble() / image.width
        assertTrue(inset > 0.07, "left inset $inset is too small, the dock icon overflows")
        assertTrue(inset < 0.14, "left inset $inset is too large, the dock icon looks sparse")
    }

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private fun opaque(image: java.awt.image.BufferedImage): Box {
        var left = image.width
        var top = image.height
        var right = 0
        var bottom = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) > 16) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }
        return Box(left, top, right, bottom)
    }
}
