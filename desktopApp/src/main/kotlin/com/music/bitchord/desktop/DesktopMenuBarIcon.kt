package com.music.bitchord.desktop

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Menu-bar glyph for macOS.
 *
 * The status item wants the waveform itself, at menu-bar height, not a shrunk copy of the
 * rounded app tile. The mark is drawn large, cropped to its ink, then scaled so that ink
 * fills the requested height.
 */
internal object DesktopMenuBarIcon {

    /**
     * Ink height in points. Menu-bar symbols sit in an 18pt slot but the drawing itself is
     * shorter, otherwise the waves fill the whole bar and look oversized.
     */
    const val POINT_HEIGHT = 13.0

    fun glyph(svg: ByteArray, heightPx: Int): BufferedImage {
        val rendered = render(svg, RASTER)
        val bounds = opaqueBounds(rendered) ?: return BufferedImage(heightPx, heightPx, BufferedImage.TYPE_INT_ARGB)
        val srcW = bounds.width().coerceAtLeast(1)
        val srcH = bounds.height().coerceAtLeast(1)
        val dstH = heightPx.coerceAtLeast(1)
        val dstW = (dstH * (srcW.toDouble() / srcH)).roundToInt().coerceAtLeast(1)
        val out = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(
            rendered,
            0,
            0,
            dstW,
            dstH,
            bounds.left,
            bounds.top,
            bounds.right + 1,
            bounds.bottom + 1,
            null,
        )
        g.dispose()
        // Template images are black ink. The mark is drawn white; keep its alpha.
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val argb = out.getRGB(x, y)
                val alpha = argb ushr 24
                out.setRGB(x, y, (alpha shl 24))
            }
        }
        return out
    }

    private fun render(svg: ByteArray, widthPx: Int): BufferedImage {
        val text = svg.toString(Charsets.UTF_8)
        val box = viewBox(text)
        val vbW = box?.third ?: widthPx.toFloat()
        val vbH = box?.fourth ?: widthPx.toFloat()
        val heightPx = (widthPx * (vbH / vbW)).roundToInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
        val dom = SVGDOM(Data.makeFromBytes(svg))
        dom.setContainerSize(widthPx.toFloat(), heightPx.toFloat())
        val intrinsic = dom.root?.width?.value?.takeIf { it > 0f } ?: vbW
        val scale = widthPx / intrinsic
        // An SVG that declares a tiny width (the mark is 28px) would otherwise paint a speck.
        // Scale from the viewBox when the declared width is not the artwork's real size.
        val viewScale = widthPx / vbW
        val used = if (intrinsic < vbW * 0.5f) viewScale else scale
        surface.canvas.scale(used, used)
        dom.render(surface.canvas)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(widthPx, heightPx))
        if (!surface.readPixels(bitmap, 0, 0)) return BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB)
        val bytes = bitmap.readPixels() ?: return BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB)
        val out = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB)
        val rowBytes = bitmap.rowBytes
        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                val at = y * rowBytes + x * 4
                if (at + 3 >= bytes.size) continue
                val b = bytes[at].toInt() and 0xFF
                val g = bytes[at + 1].toInt() and 0xFF
                val r = bytes[at + 2].toInt() and 0xFF
                val a = bytes[at + 3].toInt() and 0xFF
                out.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return out
    }

    private fun viewBox(svg: String): Quadruple? {
        val match = VIEW_BOX.find(svg) ?: return null
        val parts = match.groupValues[1].split(Regex("[,\\s]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size < 4 || parts[2] <= 0f || parts[3] <= 0f) return null
        return Quadruple(parts[0], parts[1], parts[2], parts[3])
    }

    private data class Quadruple(val first: Float, val second: Float, val third: Float, val fourth: Float)

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width() = right - left + 1
        fun height() = bottom - top + 1
    }

    private fun opaqueBounds(image: BufferedImage): Bounds? {
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = image.getRGB(x, y) ushr 24
                if (alpha > 16) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null
        return Bounds(left, top, right, bottom)
    }

    private val VIEW_BOX = Regex("""viewBox\s*=\s*"([^"]+)"""")
    private const val RASTER = 1024
}
