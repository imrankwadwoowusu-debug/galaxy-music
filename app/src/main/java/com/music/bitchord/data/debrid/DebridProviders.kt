package com.music.bitchord.data.debrid

/**
 * Debrid backends this build can talk to, by stable id.
 *
 * A second provider means a second client plus an entry here — the source,
 * the editor and the stored config all key off [id], never off a class
 * name, so nothing else has to move. TorBox is the only one wired today.
 */
object DebridProviders {

    const val TORBOX = "torbox"

    /** Human name for settings and logs. Unknown ids read back as themselves. */
    fun labelFor(id: String): String = when (id) {
        TORBOX -> "TorBox"
        else -> id.ifBlank { "Debrid" }
    }

    fun isKnown(id: String): Boolean = id == TORBOX
}
