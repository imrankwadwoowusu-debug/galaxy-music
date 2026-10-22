package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The few lines a resolve has to say about itself. */
internal object DesktopTrackLog {

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Platform state dir (`~/Library/Logs/BitChord` on macOS, XDG elsewhere) / `desktop.log`. */
    private val file: Path? by lazy {
        runCatching {
            val directory = DesktopPaths.stateHome()
            Files.createDirectories(directory)
            directory.resolve("desktop.log")
        }.getOrNull()
    }

    fun log(line: String) {
        val stamped = "${LocalTime.now().format(clock)}  $line"
        println("BitChord: $stamped")
        val target = file ?: return
        runCatching {
            Files.writeString(
                target,
                stamped + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
}
