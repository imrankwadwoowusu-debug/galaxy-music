package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Where BitChord keeps its on-disk state on each desktop OS.
 *
 * Linux follows XDG. macOS uses the standard Library layout. Windows keeps the previous
 * home-relative defaults used elsewhere in this module (prefs already go through the
 * platform store).
 */
internal object DesktopPaths {

    private val home: Path get() = Path.of(System.getProperty("user.home"))

    /** Persistent data (analyser natives and models cache, etc.). */
    fun dataHome(app: String = "bitchord"): Path {
        if (DesktopPlatform.isMac) {
            return ensureMigrated(
                target = home.resolve("Library/Application Support/BitChord"),
                legacy = xdgData(app),
            )
        }
        return xdgData(app).also { Files.createDirectories(it) }
    }

    /** Transient media / download cache. */
    fun cacheHome(app: String = "bitchord"): Path {
        if (DesktopPlatform.isMac) {
            return ensureMigrated(
                target = home.resolve("Library/Caches/BitChord"),
                legacy = xdgCache(app),
            )
        }
        return xdgCache(app).also { Files.createDirectories(it) }
    }

    /** Append-only logs / state files. */
    fun stateHome(app: String = "bitchord"): Path {
        if (DesktopPlatform.isMac) {
            return ensureMigrated(
                target = home.resolve("Library/Logs/BitChord"),
                legacy = xdgState(app),
            )
        }
        return xdgState(app).also { Files.createDirectories(it) }
    }

    private fun xdgData(app: String): Path {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: home.resolve(".local/share").toString()
        return Path.of(base, app)
    }

    private fun xdgCache(app: String): Path {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?: home.resolve(".cache").toString()
        return Path.of(base, app)
    }

    private fun xdgState(app: String): Path {
        val base = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
            ?: home.resolve(".local/state").toString()
        return Path.of(base, app)
    }

    /**
     * If an older XDG layout still has files and the Library target is empty, copy once.
     * Never deletes the legacy tree.
     */
    private fun ensureMigrated(target: Path, legacy: Path): Path {
        Files.createDirectories(target)
        if (!isEmptyDir(target)) return target
        if (!Files.isDirectory(legacy) || isEmptyDir(legacy)) return target
        runCatching {
            Files.walk(legacy).use { stream ->
                stream.forEach { source ->
                    val relative = legacy.relativize(source)
                    val destination = target.resolve(relative.toString())
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(destination)
                    } else {
                        Files.createDirectories(destination.parent)
                        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
            // println rather than DesktopTrackLog: that object's file path is resolved through
            // this helper, and a log here would re-enter the lazy initializer.
            println("BitChord: migrated desktop data from $legacy to $target")
        }.onFailure {
            println("BitChord: could not migrate $legacy to $target: ${it.message}")
        }
        return target
    }

    private fun isEmptyDir(path: Path): Boolean {
        if (!Files.isDirectory(path)) return true
        return Files.list(path).use { it.findFirst().isEmpty }
    }
}
