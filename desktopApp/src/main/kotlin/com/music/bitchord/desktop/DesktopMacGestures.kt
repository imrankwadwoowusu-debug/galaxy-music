package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.EventQueue
import kotlin.math.abs

/**
 * The system "swipe between pages" gesture.
 *
 * Two-finger scrolling stays a scroll. This is the separate swipe macOS sends
 * for going back, the same one Safari uses. A swipe to the right goes back.
 * There is no forward history, so a swipe to the left does nothing.
 */
internal object DesktopMacGestures {

    /** NSEventTypeSwipe. The mask is one bit shifted by the event type. */
    private const val SWIPE_MASK = 1L shl 31

    /** A global block is never copied, so it needs no dispose helper. */
    private const val BLOCK_IS_GLOBAL = 1 shl 28

    private var onBack: () -> Unit = {}
    private var monitor: Pointer? = null
    private var blockMemory: Memory? = null
    private var descriptorMemory: Memory? = null
    private var handler: SwipeHandler? = null

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
    }

    private interface ObjcSend : Library {
        fun objc_msgSend(receiver: Pointer, selector: Pointer, mask: Long, handler: Pointer): Pointer?
    }

    private interface ObjcRetain : Library {
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
    }

    private interface ObjcDouble : Library {
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Double
    }

    private interface SwipeHandler : Callback {
        fun invoke(block: Pointer?, event: Pointer?): Pointer?
    }

    private val objc: ObjC? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", ObjC::class.java) }.getOrNull()
    }

    private val msg: ObjcSend? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", ObjcSend::class.java) }.getOrNull()
    }

    private val retainSend: ObjcRetain? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", ObjcRetain::class.java) }.getOrNull()
    }

    private val msgDouble: ObjcDouble? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", ObjcDouble::class.java) }.getOrNull()
    }

    fun install(back: () -> Unit) {
        if (!DesktopPlatform.isMac) return
        onBack = back
        if (monitor != null) return
        val run = Runnable {
            if (monitor != null) return@Runnable
            runCatching { installOnMain() }.onFailure {
                DesktopTrackLog.log("trackpad back swipe: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        if (EventQueue.isDispatchThread()) run.run() else EventQueue.invokeLater(run)
    }

    /** Rightward page swipe. A vertical gesture is not navigation. */
    fun isBackSwipe(deltaX: Double, deltaY: Double): Boolean =
        deltaX > 0.0 && deltaX >= abs(deltaY)

    internal fun globalBlockClass(): Pointer? = runCatching {
        NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock")
    }.getOrNull()

    private fun installOnMain() {
        val runtime = objc ?: return
        val send = msg ?: return
        val isa = globalBlockClass() ?: run {
            DesktopTrackLog.log("trackpad back swipe: global block class is missing")
            return
        }
        val callback = object : SwipeHandler {
            override fun invoke(block: Pointer?, event: Pointer?): Pointer? {
                if (event != null && isBack(event)) EventQueue.invokeLater { onBack() }
                return event
            }
        }
        val descriptor = Memory(16).also {
            it.setLong(0, 0L)
            it.setLong(8, 32L)
        }
        val function = CallbackReference.getFunctionPointer(callback)
        val literal = Memory(32).also {
            it.setPointer(0, isa)
            it.setInt(8, BLOCK_IS_GLOBAL)
            it.setInt(12, 0)
            it.setPointer(16, function)
            it.setPointer(24, descriptor)
        }
        handler = callback
        descriptorMemory = descriptor
        blockMemory = literal
        val nsEvent = runtime.objc_getClass("NSEvent") ?: return
        val created = send.objc_msgSend(
            nsEvent,
            runtime.sel_registerName("addLocalMonitorForEventsMatchingMask:handler:"),
            SWIPE_MASK,
            literal,
        )
        if (created == null || created == Pointer.NULL) {
            DesktopTrackLog.log("trackpad back swipe: monitor was not installed")
            return
        }
        monitor = retainSend?.objc_msgSend(created, runtime.sel_registerName("retain")) ?: created
        DesktopTrackLog.log("trackpad back swipe installed")
    }

    private fun isBack(event: Pointer): Boolean {
        val send = msgDouble ?: return false
        val runtime = objc ?: return false
        val deltaX = send.objc_msgSend(event, runtime.sel_registerName("deltaX"))
        val deltaY = send.objc_msgSend(event, runtime.sel_registerName("deltaY"))
        return isBackSwipe(deltaX, deltaY)
    }
}
