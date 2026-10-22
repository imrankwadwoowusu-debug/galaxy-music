package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * macOS menu-bar status item.
 *
 * AWT's tray draws the full app tile into a ~22px slot, so the mark comes out as a speck.
 * This is an NSStatusItem whose image is the waveform at menu-bar size, marked as a template
 * so it follows the menu bar's light and dark appearance.
 */
internal object DesktopMacStatusItem {

    /** NSSquareStatusItemLength. A square the height of the menu bar, until the glyph sets its own width. */
    private const val SQUARE_LENGTH = -2.0

    private const val LEFT_MOUSE_UP = 2L
    private const val RIGHT_MOUSE_DOWN = 3L
    private const val RIGHT_MOUSE_UP = 4L
    private const val CONTROL = 1L shl 18
    private const val LEFT_AND_RIGHT_CLICK = 4L or 8L or 16L


    private var item: Pointer? = null
    private var statusMenu: Pointer? = null
    private var dockMenu: Pointer? = null
    private var onActivate: (() -> Unit)? = null
    private var pendingSvg: ByteArray? = null
    private var dockMenuInstalled = false

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, name: Pointer, imp: Callback, types: String): Boolean
        fun class_getInstanceMethod(cls: Pointer, name: Pointer): Pointer?
        fun method_setImplementation(method: Pointer, imp: Callback): Pointer?
    }

    /** `applicationDockMenu:` returns the menu, so this callback cannot be void. */
    private interface DockMenuCallback : Callback {
        fun invoke(self: Pointer, cmd: Pointer, sender: Pointer): Pointer
    }

    private interface MenuCallback : Callback {
        fun invoke(self: Pointer, cmd: Pointer, sender: Pointer)
    }

    private val objc: ObjC? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", ObjC::class.java) }.getOrNull()
    }

    private val msg by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }.getOrNull()
    }

    /** CGFloat arguments have to go through a typed call. An untyped send drops the length and the item collapses to nothing. */
    private interface ObjcDouble : Library {
        fun objc_msgSend(receiver: Pointer, selector: Pointer, value: Double): Pointer?
    }

    private val msgDouble: ObjcDouble? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", ObjcDouble::class.java) }.getOrNull()
    }

    private val jobs = ArrayDeque<() -> Unit>()

    private val callback = object : MenuCallback {
        override fun invoke(self: Pointer, cmd: Pointer, sender: Pointer) {
            val command = objc?.sel_registerName("install:")
            if (command != null && Pointer.nativeValue(cmd) == Pointer.nativeValue(command)) {
                val pending = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
                pending.forEach { job ->
                    runCatching(job).onFailure {
                        DesktopTrackLog.log("menu bar status item: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
                return
            }
            if (same(cmd, sel("statusClick:"))) {
                if (isContextClick()) popUpMenu() else EventQueue.invokeLater { onActivate?.invoke() }
                return
            }
            val tag = (msg?.invoke(Long::class.java, arrayOf(sender, sel("tag"))) as? Number)?.toInt() ?: return
            if (tag == 0) {
                EventQueue.invokeLater { onActivate?.invoke() }
            } else {
                DesktopTrayMenu.perform(tag)
            }
        }
    }

    private val dockMenuCallback = object : DockMenuCallback {
        override fun invoke(self: Pointer, cmd: Pointer, sender: Pointer): Pointer =
            runCatching { menu(dock = true) }.getOrNull() ?: Pointer.NULL
    }

    private val helper: Pointer? by lazy { installHelper() }

    fun start(activate: () -> Unit) {
        onActivate = activate
        DesktopTrayMenu.onLayoutChanged { update() }
        onMain {
            if (item != null) return@onMain
            val created = statusItem() ?: run {
                DesktopTrackLog.log("menu bar status item could not be created")
                return@onMain
            }
            item = retain(created)
            wireClick()
            pendingSvg?.let { applyGlyph(it) }
            installDockMenu()
            DesktopTrackLog.log("menu bar status item installed")
        }
    }

    fun setIcon(svg: ByteArray) {
        pendingSvg = svg
        onMain {
            if (item == null) return@onMain
            applyGlyph(svg)
        }
    }

    fun update() {
        // The Dock asks for its menu when it opens, and the status menu is built on right-click.
    }

    fun stop() {
        onMain {
            val current = item ?: return@onMain
            item = null
            val bar = statusBar() ?: return@onMain
            msg?.invoke(Void.TYPE, arrayOf(bar, sel("removeStatusItem:"), current))
        }
    }

    private fun applyGlyph(svg: ByteArray) {
        val button = msg?.invoke(Pointer::class.java, arrayOf(item, sel("button"))) as? Pointer ?: return
        val scale = Toolkit.getDefaultToolkit().screenResolution / 72.0
        val pixels = (DesktopMenuBarIcon.POINT_HEIGHT * scale).roundToInt().coerceAtLeast(18)
        val image = DesktopMenuBarIcon.glyph(svg, pixels)
        val png = pngWithDpi(image, (72.0 * scale).roundToInt().coerceAtLeast(72))
        val nsImage = image(png)
        if (nsImage == null) {
            DesktopTrackLog.log("menu bar glyph could not be decoded (${image.width}x${image.height})")
            return
        }
        msg?.invoke(Void.TYPE, arrayOf(nsImage, sel("setTemplate:"), 1.toByte()))
        msg?.invoke(Void.TYPE, arrayOf(button, sel("setImage:"), nsImage))
        val pointWidth = image.width / scale
        val current = item ?: return
        msgDouble?.objc_msgSend(current, sel("setLength:"), pointWidth + 2.0)
        DesktopTrackLog.log("menu bar glyph ${image.width}x${image.height} at ${pointWidth.toInt()}pt")
    }

    /** Left click raises the window. Right click, and control-click, open the menu. */
    private fun wireClick() {
        val button = msg?.invoke(Pointer::class.java, arrayOf(item, sel("button"))) as? Pointer ?: return
        val target = helper ?: return
        msg?.invoke(Void.TYPE, arrayOf(button, sel("setTarget:"), target))
        msg?.invoke(Void.TYPE, arrayOf(button, sel("setAction:"), sel("statusClick:")))
        // Left mouse up and both right-click edges. The default only delivers the left click.
        msg?.invoke(Void.TYPE, arrayOf(button, sel("sendActionOn:"), LEFT_AND_RIGHT_CLICK))
    }

    private fun popUpMenu() {
        val current = item ?: return
        val built = menu(dock = false)
        msg?.invoke(Void.TYPE, arrayOf(current, sel("popUpStatusItemMenu:"), built))
    }

    private fun isContextClick(): Boolean {
        val app = msg?.invoke(Pointer::class.java, arrayOf(cls("NSApplication"), sel("sharedApplication"))) as? Pointer
            ?: return false
        val event = msg?.invoke(Pointer::class.java, arrayOf(app, sel("currentEvent"))) as? Pointer ?: return false
        val type = (msg?.invoke(Long::class.java, arrayOf(event, sel("type"))) as? Number)?.toLong() ?: return false
        val flags = (msg?.invoke(Long::class.java, arrayOf(event, sel("modifierFlags"))) as? Number)?.toLong() ?: 0L
        val rightClick = type == RIGHT_MOUSE_DOWN || type == RIGHT_MOUSE_UP
        val controlClick = type == LEFT_MOUSE_UP && flags and CONTROL != 0L
        return rightClick || controlClick
    }

    /**
     * Right-click on the Dock icon. AWT already implements `applicationDockMenu:` and returns
     * nothing useful, and the Java wrapper for that menu is not exported. The implementation
     * is replaced with the same menu as the status item.
     */
    private fun installDockMenu() {
        if (dockMenuInstalled) return
        val runtime = objc ?: return
        val app = msg?.invoke(Pointer::class.java, arrayOf(cls("NSApplication"), sel("sharedApplication"))) as? Pointer
            ?: return
        val delegate = msg?.invoke(Pointer::class.java, arrayOf(app, sel("delegate"))) as? Pointer ?: return
        val delegateClass = msg?.invoke(Pointer::class.java, arrayOf(delegate, sel("class"))) as? Pointer ?: return
        val selector = sel("applicationDockMenu:")
        val existing = runtime.class_getInstanceMethod(delegateClass, selector)
        val replaced = if (existing != null && existing != Pointer.NULL) {
            runtime.method_setImplementation(existing, dockMenuCallback) != null
        } else {
            runtime.class_addMethod(delegateClass, selector, dockMenuCallback, "@@:@")
        }
        dockMenuInstalled = replaced
        DesktopTrackLog.log(if (replaced) "dock menu installed" else "dock menu could not be installed")
    }

    /**
     * Rebuilt when it opens. The Dock keeps transport only. The status item adds Open,
     * Settings and Quit, with the same labels as the application menu.
     */
    private fun menu(dock: Boolean): Pointer {
        val existing = if (dock) dockMenu else statusMenu
        if (existing != null) {
            msg?.invoke(Void.TYPE, arrayOf(existing, sel("removeAllItems")))
            fillMenu(existing, dock)
            return existing
        }
        val allocated = msg?.invoke(Pointer::class.java, arrayOf(cls("NSMenu"), sel("alloc"))) as? Pointer
            ?: return Pointer.NULL
        val created = retain(msg?.invoke(Pointer::class.java, arrayOf(allocated, sel("init"))) as? Pointer ?: allocated)
        if (dock) dockMenu = created else statusMenu = created
        fillMenu(created, dock)
        return created
    }

    private fun fillMenu(menu: Pointer, dock: Boolean) {
        val target = helper
        val entries = if (dock) DesktopTrayMenu.entriesForDock() else DesktopTrayMenu.entriesForNative()
        entries.forEach { entry ->
            val menuItem = if (entry.separator) {
                msg?.invoke(Pointer::class.java, arrayOf(cls("NSMenuItem"), sel("separatorItem"))) as? Pointer
            } else {
                val created = msg?.invoke(Pointer::class.java, arrayOf(cls("NSMenuItem"), sel("alloc"))) as? Pointer
                    ?: return@forEach
                msg?.invoke(Pointer::class.java, arrayOf(created, sel("init")))
                nsString(entry.label)?.let { title ->
                    msg?.invoke(Void.TYPE, arrayOf(created, sel("setTitle:"), title))
                }
                msg?.invoke(Void.TYPE, arrayOf(created, sel("setEnabled:"), if (entry.enabled) 1.toByte() else 0.toByte()))
                if (entry.enabled && target != null) {
                    msg?.invoke(Void.TYPE, arrayOf(created, sel("setTarget:"), target))
                    msg?.invoke(Void.TYPE, arrayOf(created, sel("setAction:"), sel("menuAction:")))
                    msg?.invoke(Void.TYPE, arrayOf(created, sel("setTag:"), entry.id.toLong()))
                }
                created
            } ?: return@forEach
            msg?.invoke(Void.TYPE, arrayOf(menu, sel("addItem:"), menuItem))
        }
    }

    private fun statusItem(): Pointer? {
        val bar = statusBar() ?: return null
        // NSSquareStatusItemLength. A variable length passed without a CGFloat register comes out as zero width.
        val created = msgDouble?.objc_msgSend(bar, sel("statusItemWithLength:"), SQUARE_LENGTH)
        return created
    }

    private fun statusBar(): Pointer? {
        val type = cls("NSStatusBar") ?: return null
        return msg?.invoke(Pointer::class.java, arrayOf(type, sel("systemStatusBar"))) as? Pointer
    }

    private fun image(png: ByteArray): Pointer? {
        val data = nsData(png) ?: return null
        val allocated = msg?.invoke(Pointer::class.java, arrayOf(cls("NSImage"), sel("alloc"))) as? Pointer ?: return null
        return msg?.invoke(Pointer::class.java, arrayOf(allocated, sel("initWithData:"), data)) as? Pointer
    }

    private fun nsData(bytes: ByteArray): Pointer? {
        val memory = Memory(bytes.size.toLong())
        memory.write(0, bytes, 0, bytes.size)
        return msg?.invoke(
            Pointer::class.java,
            arrayOf(cls("NSData"), sel("dataWithBytes:length:"), memory, bytes.size.toLong()),
        ) as? Pointer
    }

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

    private fun retain(pointer: Pointer): Pointer =
        msg?.invoke(Pointer::class.java, arrayOf(pointer, sel("retain"))) as? Pointer ?: pointer

    private fun installHelper(): Pointer? {
        val o = objc ?: return null
        val nsObject = o.objc_getClass("NSObject") ?: return null
        val name = "BitChordStatusItemTarget"
        var cls = o.objc_getClass(name)
        if (cls == null) {
            cls = o.objc_allocateClassPair(nsObject, name, 0L) ?: return null
            o.class_addMethod(cls, o.sel_registerName("menuAction:"), callback, "v@:@")
            o.class_addMethod(cls, o.sel_registerName("statusClick:"), callback, "v@:@")
            o.class_addMethod(cls, o.sel_registerName("install:"), callback, "v@:@")
            o.objc_registerClassPair(cls)
        }
        val allocated = msg?.invoke(Pointer::class.java, arrayOf(cls, sel("alloc"))) as? Pointer ?: return null
        return msg?.invoke(Pointer::class.java, arrayOf(allocated, sel("init"))) as? Pointer
    }

    private fun cls(name: String): Pointer? = objc?.objc_getClass(name)

    private fun sel(name: String): Pointer = objc?.sel_registerName(name) ?: Pointer.NULL

    private fun same(a: Pointer, b: Pointer): Boolean = Pointer.nativeValue(a) == Pointer.nativeValue(b)

    /**
     * NSStatusItem creates a window, and AppKit only allows that on the process main thread.
     * Compose's dispatcher is not always that thread.
     */
    private fun onMain(block: () -> Unit) {
        if (isAppKitMain()) {
            runCatching(block).onFailure {
                DesktopTrackLog.log("menu bar status item: ${it.javaClass.simpleName}: ${it.message}")
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

    /** ImageIO writes 72 dpi. A Retina bitmap would then be reported as twice the point size. */
    private fun pngWithDpi(image: BufferedImage, dpi: Int): ByteArray {
        val raw = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return insertPhys(raw, dpi)
    }

    private fun insertPhys(png: ByteArray, dpi: Int): ByteArray {
        if (png.size < 33 || png[0] != 0x89.toByte()) return png
        val ppm = (dpi / 0.0254).roundToInt()
        // length(4) + type(4) + 9 data bytes + crc(4). Extra trailing bytes corrupt the PNG and AppKit drops it.
        val chunk = ByteArray(21)
        chunk[3] = 9
        chunk[4] = 'p'.code.toByte()
        chunk[5] = 'H'.code.toByte()
        chunk[6] = 'Y'.code.toByte()
        chunk[7] = 's'.code.toByte()
        writeInt(chunk, 8, ppm)
        writeInt(chunk, 12, ppm)
        chunk[16] = 1
        val crc = java.util.zip.CRC32()
        crc.update(chunk, 4, 13)
        writeInt(chunk, 17, crc.value.toInt())
        val ihdrEnd = 8 + 8 + 13 + 4
        return png.copyOfRange(0, ihdrEnd) + chunk + png.copyOfRange(ihdrEnd, png.size)
    }

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }
}
