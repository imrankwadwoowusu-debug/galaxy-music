package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.EventQueue
import java.awt.Toolkit

/**
 * The macOS application menu, the one that sits next to the Apple menu.
 *
 * Java leaves that bar as a generic application menu and, without an Edit menu, text fields
 * lose Cut, Copy and Paste. This installs the menus a Mac app is expected to have and points
 * the playback and settings rows at the player.
 */
internal object DesktopMacAppMenu {

    private const val TAG_SETTINGS = 1
    private const val TAG_PLAY = 2
    private const val TAG_NEXT = 3
    private const val TAG_PREVIOUS = 4
    private const val TAG_QUIT = 5
    private const val TAG_FULLSCREEN = 6
    private const val COMMAND = 1L shl 20
    private const val OPTION = 1L shl 19
    private const val CONTROL = 1L shl 18
    private const val SHIFT = 1L shl 17

    private var installed = false
    private var playItem: Pointer? = null
    private var trackItem: Pointer? = null
    private var fullScreenItem: Pointer? = null
    private var onSettings: () -> Unit = {}
    private var onPlayPause: () -> Unit = {}
    private var onNext: () -> Unit = {}
    private var onPrevious: () -> Unit = {}
    private var onQuit: () -> Unit = {}

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, name: Pointer, imp: Callback, types: String): Boolean
    }

    private interface MenuCallback : Callback {
        fun invoke(self: Pointer, cmd: Pointer, sender: Pointer)
    }

    private val objc: ObjC? by lazy {
        if (!DesktopPlatform.isMac) null else runCatching { Native.load("objc", ObjC::class.java) }.getOrNull()
    }
    private val msg by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }.getOrNull()
    }
    private val jobs = ArrayDeque<() -> Unit>()

    private val callback = object : MenuCallback {
        override fun invoke(self: Pointer, cmd: Pointer, sender: Pointer) {
            if (same(cmd, sel("install:"))) {
                val pending = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
                pending.forEach { job ->
                    runCatching(job).onFailure {
                        DesktopTrackLog.log("mac menu: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
                return
            }
            val tag = (msg?.invoke(Long::class.java, arrayOf(sender, sel("tag"))) as? Number)?.toInt() ?: return
            val action = when (tag) {
                TAG_SETTINGS -> onSettings
                TAG_PLAY -> onPlayPause
                TAG_NEXT -> onNext
                TAG_PREVIOUS -> onPrevious
                TAG_QUIT -> onQuit
                TAG_FULLSCREEN -> ({ DesktopWindowMode.toggle() })
                else -> return
            }
            EventQueue.invokeLater(action)
        }
    }

    private val helper: Pointer? by lazy { installHelper() }

    fun install() {
        if (!DesktopPlatform.isMac) return
        onMain {
            if (installed) return@onMain
            val app = nsApp() ?: return@onMain
            val main = menu("")
            addSubmenu(main, "BitChord", appMenu(app))
            addSubmenu(main, text("d_mac_edit", "Edit"), editMenu())
            addSubmenu(main, text("d_mac_playback", "Playback"), playbackMenu())
            val window = windowMenu()
            addSubmenu(main, text("d_mac_window", "Window"), window)
            msg?.invoke(Void.TYPE, arrayOf(app, sel("setWindowsMenu:"), window))
            msg?.invoke(Void.TYPE, arrayOf(app, sel("setMainMenu:"), main))
            installed = true
            DesktopTrackLog.log("mac application menu installed")
        }
    }

    fun bind(
        settings: () -> Unit,
        playPause: () -> Unit,
        next: () -> Unit,
        previous: () -> Unit,
        quit: () -> Unit,
    ) {
        onSettings = settings
        onPlayPause = playPause
        onNext = next
        onPrevious = previous
        onQuit = quit
    }

    fun refresh() {
        if (!DesktopPlatform.isMac) return
        onMain {
            playItem?.let { item ->
                nsString(DesktopTrayMenu.playPauseLabel())?.let { title ->
                    msg?.invoke(Void.TYPE, arrayOf(item, sel("setTitle:"), title))
                }
            }
            fullScreenItem?.let { item -> setTitle(item, fullScreenItemTitle()) }
            val track = trackItem ?: return@onMain
            val title = DesktopTrayMenu.nowPlayingTitle()
            if (title.isNullOrBlank()) {
                msg?.invoke(Void.TYPE, arrayOf(track, sel("setHidden:"), 1.toByte()))
            } else {
                nsString(title)?.let { label ->
                    msg?.invoke(Void.TYPE, arrayOf(track, sel("setTitle:"), label))
                }
                msg?.invoke(Void.TYPE, arrayOf(track, sel("setHidden:"), 0.toByte()))
            }
        }
    }

    private fun appMenu(app: Pointer): Pointer = menu("BitChord").also { menu ->
        add(menu, systemItem(text("d_mac_about", "About BitChord"), "orderFrontStandardAboutPanel:", app))
        add(menu, separator())
        add(menu, customItem(DesktopTrayMenu.settingsLabel(), TAG_SETTINGS, ","))
        add(menu, separator())
        add(menu, systemItem(text("d_mac_hide", "Hide BitChord"), "hide:", app, "h"))
        add(menu, systemItem(text("d_mac_hide_others", "Hide Others"), "hideOtherApplications:", app, "h", COMMAND or OPTION))
        add(menu, systemItem(text("d_mac_show_all", "Show All"), "unhideAllApplications:", app))
        add(menu, separator())
        add(menu, customItem(text("d_quit_bitchord", "Quit BitChord"), TAG_QUIT, "q"))
    }

    private fun editMenu(): Pointer = menu(text("d_mac_edit", "Edit")).also { menu ->
        add(menu, responderItem(text("d_mac_undo", "Undo"), "undo:", "z"))
        add(menu, responderItem(text("d_mac_redo", "Redo"), "redo:", "Z", COMMAND or SHIFT))
        add(menu, separator())
        add(menu, responderItem(text("d_mac_cut", "Cut"), "cut:", "x"))
        add(menu, responderItem(text("d_mac_copy", "Copy"), "copy:", "c"))
        add(menu, responderItem(text("d_mac_paste", "Paste"), "paste:", "v"))
        add(menu, responderItem(text("d_mac_select_all", "Select All"), "selectAll:", "a"))
    }

    private fun playbackMenu(): Pointer = menu(text("d_mac_playback", "Playback")).also { menu ->
        val track = blankItem()
        setTitle(track, " ")
        msg?.invoke(Void.TYPE, arrayOf(track, sel("setEnabled:"), 0.toByte()))
        msg?.invoke(Void.TYPE, arrayOf(track, sel("setHidden:"), 1.toByte()))
        trackItem = track
        add(menu, track)
        val play = customItem(DesktopTrayMenu.playPauseLabel(), TAG_PLAY, "")
        playItem = play
        add(menu, play)
        add(menu, customItem(text("widget_next", "Next"), TAG_NEXT, ""))
        add(menu, customItem(text("widget_previous", "Previous"), TAG_PREVIOUS, ""))
    }

    private fun windowMenu(): Pointer = menu(text("d_mac_window", "Window")).also { menu ->
        add(menu, responderItem(text("d_mac_minimize", "Minimize"), "performMiniaturize:", "m"))
        add(menu, responderItem(text("d_mac_zoom", "Zoom"), "performZoom:", ""))
        fullScreenItem = customItem(fullScreenItemTitle(), TAG_FULLSCREEN, "f", COMMAND or CONTROL)
        fullScreenItem?.let { add(menu, it) }
        add(menu, separator())
        add(menu, responderItem(text("d_mac_close", "Close"), "performClose:", "w"))
        add(menu, systemItem(text("d_mac_bring_front", "Bring All to Front"), "arrangeInFront:", nsApp()))
    }

    private fun addSubmenu(main: Pointer, title: String, submenu: Pointer) {
        val item = blankItem()
        nsString(title)?.let { msg?.invoke(Void.TYPE, arrayOf(submenu, sel("setTitle:"), it)) }
        msg?.invoke(Void.TYPE, arrayOf(item, sel("setSubmenu:"), submenu))
        add(main, item)
    }

    private fun fullScreenItemTitle(): String = fullScreenMenuTitle(
        DesktopWindowMode.fullScreen.value,
        text("d_mac_enter_full_screen", "Enter Full Screen"),
        text("d_mac_exit_full_screen", "Exit Full Screen"),
    )

    private fun customItem(title: String, tag: Int, key: String, modifiers: Long? = null): Pointer = blankItem().also { item ->
        setTitle(item, title)
        key(item, key, modifiers)
        val target = helper
        if (target != null) {
            msg?.invoke(Void.TYPE, arrayOf(item, sel("setTarget:"), target))
            msg?.invoke(Void.TYPE, arrayOf(item, sel("setAction:"), sel("menuAction:")))
            msg?.invoke(Void.TYPE, arrayOf(item, sel("setTag:"), tag.toLong()))
        }
    }

    /** Target is nil, so the key window's text field receives Cut, Copy and Paste. */
    private fun responderItem(title: String, action: String, key: String, modifiers: Long? = null): Pointer =
        blankItem().also { item ->
            setTitle(item, title)
            key(item, key, modifiers)
            msg?.invoke(Void.TYPE, arrayOf(item, sel("setAction:"), sel(action)))
        }

    private fun systemItem(
        title: String,
        action: String,
        target: Pointer?,
        key: String = "",
        modifiers: Long? = null,
    ): Pointer = blankItem().also { item ->
        setTitle(item, title)
        key(item, key, modifiers)
        if (target != null) msg?.invoke(Void.TYPE, arrayOf(item, sel("setTarget:"), target))
        msg?.invoke(Void.TYPE, arrayOf(item, sel("setAction:"), sel(action)))
    }

    private fun separator(): Pointer =
        msg?.invoke(Pointer::class.java, arrayOf(cls("NSMenuItem"), sel("separatorItem"))) as? Pointer
            ?: blankItem()

    private fun blankItem(): Pointer {
        val allocated = msg?.invoke(Pointer::class.java, arrayOf(cls("NSMenuItem"), sel("alloc"))) as? Pointer
            ?: return Pointer.NULL
        return msg?.invoke(Pointer::class.java, arrayOf(allocated, sel("init"))) as? Pointer ?: allocated
    }

    private fun menu(title: String): Pointer {
        val allocated = msg?.invoke(Pointer::class.java, arrayOf(cls("NSMenu"), sel("alloc"))) as? Pointer
            ?: return Pointer.NULL
        val created = msg?.invoke(Pointer::class.java, arrayOf(allocated, sel("init"))) as? Pointer ?: allocated
        setTitle(created, title)
        return created
    }

    private fun add(menu: Pointer, item: Pointer) {
        msg?.invoke(Void.TYPE, arrayOf(menu, sel("addItem:"), item))
    }

    private fun setTitle(item: Pointer, label: String) {
        nsString(label)?.let { msg?.invoke(Void.TYPE, arrayOf(item, sel("setTitle:"), it)) }
    }

    private fun key(item: Pointer, key: String, modifiers: Long? = null) {
        if (key.isEmpty()) return
        nsString(key)?.let { msg?.invoke(Void.TYPE, arrayOf(item, sel("setKeyEquivalent:"), it)) }
        if (modifiers != null) {
            msg?.invoke(Void.TYPE, arrayOf(item, sel("setKeyEquivalentModifierMask:"), modifiers))
        }
    }

    private fun text(key: String, fallback: String) = DesktopStrings[key, fallback]

    private fun nsApp(): Pointer? =
        msg?.invoke(Pointer::class.java, arrayOf(cls("NSApplication"), sel("sharedApplication"))) as? Pointer

    private fun nsString(value: String): Pointer? {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val memory = Memory((bytes.size + 1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        memory.setByte(bytes.size.toLong(), 0)
        return msg?.invoke(
            Pointer::class.java,
            arrayOf(cls("NSString"), sel("stringWithUTF8String:"), memory),
        ) as? Pointer
    }

    private fun installHelper(): Pointer? {
        val runtime = objc ?: return null
        val nsObject = runtime.objc_getClass("NSObject") ?: return null
        val name = "BitChordAppMenuTarget"
        var type = runtime.objc_getClass(name)
        if (type == null) {
            type = runtime.objc_allocateClassPair(nsObject, name, 0L) ?: return null
            runtime.class_addMethod(type, runtime.sel_registerName("menuAction:"), callback, "v@:@")
            runtime.class_addMethod(type, runtime.sel_registerName("install:"), callback, "v@:@")
            runtime.objc_registerClassPair(type)
        }
        val allocated = msg?.invoke(Pointer::class.java, arrayOf(type, sel("alloc"))) as? Pointer ?: return null
        return msg?.invoke(Pointer::class.java, arrayOf(allocated, sel("init"))) as? Pointer
    }

    private fun onMain(block: () -> Unit) {
        if (isAppKitMain()) {
            runCatching(block).onFailure {
                DesktopTrackLog.log("mac menu: ${it.javaClass.simpleName}: ${it.message}")
            }
            return
        }
        synchronized(jobs) { jobs.add(block) }
        Toolkit.getDefaultToolkit()
        val utilities = objc?.objc_getClass("ThreadUtilities") ?: return
        val target = helper ?: return
        msg?.invoke(
            Void.TYPE,
            arrayOf(
                utilities,
                sel("performOnMainThread:on:withObject:waitUntilDone:"),
                sel("install:"),
                target,
                target,
                0.toByte(),
            ),
        )
    }

    private fun isAppKitMain(): Boolean {
        val type = cls("NSThread") ?: return false
        return msg?.invoke(Boolean::class.java, arrayOf(type, sel("isMainThread"))) as? Boolean == true
    }

    private fun cls(name: String): Pointer? = objc?.objc_getClass(name)

    private fun sel(name: String): Pointer = objc?.sel_registerName(name) ?: Pointer.NULL

    private fun same(left: Pointer, right: Pointer): Boolean =
        Pointer.nativeValue(left) == Pointer.nativeValue(right)
}

/** The Window menu row, which swaps once the window is in the full-screen Space. */
internal fun fullScreenMenuTitle(inFullScreen: Boolean, enter: String, exit: String): String =
    if (inFullScreen) exit else enter

/**
 * F11 is Show Desktop on macOS. Full screen there is Control-Command-F, from the Window menu.
 * Elsewhere F11 still fills the screen.
 */
internal fun desktopHandlesF11(mac: Boolean): Boolean = !mac
