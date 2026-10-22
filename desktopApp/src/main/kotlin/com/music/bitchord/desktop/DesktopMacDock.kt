package com.music.bitchord.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.EventQueue

/**
 * The Dock badge.
 *
 * While tracks are still being saved, the icon carries the count, the way Mail carries
 * unread messages. A failure stays in the download list and does not keep a number on the icon.
 * An empty count clears the badge.
 */
internal object DesktopMacDock {

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
    }

    private val objc: ObjC? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", ObjC::class.java) }.getOrNull()
    }

    private val msg by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }.getOrNull()
    }

    fun setBadge(label: String?) {
        if (!DesktopPlatform.isMac) return
        val apply = Runnable {
            runCatching { applyBadge(label) }.onFailure {
                DesktopTrackLog.log("dock badge: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        if (EventQueue.isDispatchThread()) apply.run() else EventQueue.invokeLater(apply)
    }

    private fun applyBadge(label: String?) {
        val runtime = objc ?: return
        val send = msg ?: return
        val appClass = runtime.objc_getClass("NSApplication") ?: return
        val app = send.invokePointer(arrayOf(appClass, runtime.sel_registerName("sharedApplication"))) ?: return
        val tile = send.invokePointer(arrayOf(app, runtime.sel_registerName("dockTile"))) ?: return
        val text = if (label.isNullOrEmpty()) Pointer.NULL else nsString(label) ?: return
        send.invoke(arrayOf(tile, runtime.sel_registerName("setBadgeLabel:"), text))
    }

    private fun nsString(value: String): Pointer? {
        val runtime = objc ?: return null
        val send = msg ?: return null
        val nsString = runtime.objc_getClass("NSString") ?: return null
        val bytes = value.toByteArray(Charsets.UTF_8)
        val memory = Memory((bytes.size + 1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        memory.setByte(bytes.size.toLong(), 0)
        return send.invokePointer(arrayOf(nsString, runtime.sel_registerName("stringWithUTF8String:"), memory))
    }
}

/** What the Dock icon shows. Past 99 the tile would not fit the number, so it caps. */
internal fun dockBadgeLabel(inFlight: Int): String? = when {
    inFlight <= 0 -> null
    inFlight > 99 -> "99+"
    else -> inFlight.toString()
}
