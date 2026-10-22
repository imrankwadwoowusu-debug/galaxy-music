package com.music.bitchord.desktop

import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The Dock icon for a dev run.
 *
 * A packaged app takes its icon from the icns. `gradlew run` does not: it shows whatever
 * the window icon rasterised to, which is a small pre-rounded tile. This installs the same
 * full-bleed artwork the icns is built from.
 */
internal object DesktopDockIcon {

    fun install() {
        if (!DesktopPlatform.isMac) return
        if (!Taskbar.isTaskbarSupported()) return
        val taskbar = Taskbar.getTaskbar()
        if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
        val master = javaClass.getResourceAsStream("/macos/dock.png")?.use { ImageIO.read(it) } ?: run {
            DesktopTrackLog.log("dock icon missing from this build")
            return
        }
        val sizes = intArrayOf(16, 32, 64, 128, 256, 512, 1024)
        val variants: Array<Image> = sizes.map { size ->
            if (size == master.width) master else scale(master, size)
        }.toTypedArray()
        taskbar.iconImage = BaseMultiResolutionImage(variants.last(), *variants)
    }

    private fun scale(src: BufferedImage, size: Int): BufferedImage {
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, size, size, null)
        g.dispose()
        return out
    }
}
