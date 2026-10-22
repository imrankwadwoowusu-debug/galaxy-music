package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.awt.Desktop
import java.nio.file.Path

/**
 * Shows a saved track in the system file manager, with the file selected.
 *
 * On macOS that is Finder. The playing row often has no path of its own — the copy
 * on disk is the download record — so the record is what gets selected.
 */
internal fun songToReveal(song: Song, savedCopies: List<Song>): Song? {
    if (DesktopDownloadManager.savedFile(song) != null) return song
    if (song.videoId.isBlank()) return null
    return savedCopies.firstOrNull {
        it.videoId == song.videoId && DesktopDownloadManager.savedFile(it) != null
    }
}

internal fun revealLabelKey(mac: Boolean, windows: Boolean): String = when {
    mac -> "d_show_in_finder"
    windows -> "d_show_in_explorer"
    else -> "d_show_in_files"
}

internal fun revealLabelFallback(mac: Boolean, windows: Boolean): String = when {
    mac -> "Show in Finder"
    windows -> "Show in Explorer"
    else -> "Show in Files"
}

internal fun revealInFolder(path: Path): Boolean {
    val file = path.toFile()
    if (!file.isFile) return false
    return runCatching {
        val desktop = Desktop.getDesktop()
        val parent = file.parentFile
        when {
            desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> {
                desktop.browseFileDirectory(file)
                true
            }
            parent != null && desktop.isSupported(Desktop.Action.OPEN) -> {
                desktop.open(parent)
                true
            }
            else -> false
        }
    }.getOrElse {
        DesktopTrackLog.log("reveal: ${it.javaClass.simpleName}: ${it.message}")
        false
    }
}
