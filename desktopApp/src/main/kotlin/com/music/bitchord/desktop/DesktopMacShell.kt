package com.music.bitchord.desktop

/**
 * Identity the JDK reads once, before AWT exists.
 *
 * After the toolkit starts, the process name, the About panel and the Dock
 * title are already "java". These have to be set from [main] before anything
 * else touches a window.
 */
internal object DesktopMacShell {

    fun prepare() {
        if (!DesktopPlatform.isMac) return
        System.setProperty("apple.awt.application.name", "BitChord")
        System.setProperty("apple.awt.application.appearance", "system")
    }
}
