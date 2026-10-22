package com.music.bitchord.desktop

/** Which desktop this is running on. */
internal object DesktopPlatform {

    private val name: String = System.getProperty("os.name").orEmpty()

    private val arch: String = System.getProperty("os.arch").orEmpty().lowercase()

    val isWindows: Boolean = name.startsWith("Windows", ignoreCase = true)

    val isLinux: Boolean = name.contains("linux", ignoreCase = true)

    val isMac: Boolean = name.contains("mac", ignoreCase = true)

    /** Apple Silicon (and other aarch64 hosts). */
    val isArm64: Boolean = arch == "aarch64" || arch == "arm64"

    /** Whether the application draws the window's own frame instead of the system drawing it. */
    val drawsOwnWindowFrame: Boolean = isWindows || isMac
}
