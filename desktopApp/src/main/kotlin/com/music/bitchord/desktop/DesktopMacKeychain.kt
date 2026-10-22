package com.music.bitchord.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * macOS login Keychain (generic passwords) via Security.framework.
 *
 * SecItem dictionaries are keyed by the exported `kSec*` CFString constants, not by the
 * four-character attribute tags those constants point at.
 */
internal object DesktopMacKeychain {

    private const val ERR_SUCCESS = 0
    private const val ERR_ITEM_NOT_FOUND = -25300
    private const val ERR_DUPLICATE_ITEM = -25299

    private val available: Boolean by lazy {
        DesktopPlatform.isMac && security != null && cf != null && secClass != null && secClassGenericPassword != null
    }

    fun isAvailable(): Boolean = available

    /** Password bytes for a generic-password item, or null when missing or denied. */
    fun findGenericPassword(service: String, account: String): ByteArray? {
        if (!available) return null
        val query = query(
            service,
            account,
            extra = listOf(
                Entry(symbol("kSecReturnData"), cfBoolean(true), owned = false),
                Entry(symbol("kSecMatchLimit"), symbol("kSecMatchLimitOne"), owned = false),
            ),
        ) ?: return null
        val result = PointerByReference()
        return try {
            val status = security!!.SecItemCopyMatching(query, result)
            when (status) {
                ERR_ITEM_NOT_FOUND -> null
                ERR_SUCCESS -> dataBytes(result.value)
                else -> {
                    DesktopTrackLog.log("keychain lookup failed ($status) for $service/$account")
                    null
                }
            }
        } finally {
            cfRelease(query)
        }
    }

    /** Stores [secret] under [service]/[account], replacing any existing item. */
    fun storeGenericPassword(service: String, account: String, label: String, secret: ByteArray): Boolean {
        if (!available) return false
        deleteGenericPassword(service, account)
        val attributes = query(
            service,
            account,
            extra = listOf(
                Entry(symbol("kSecAttrLabel"), cfString(label), owned = true),
                Entry(symbol("kSecValueData"), cfData(secret), owned = true),
            ),
        ) ?: return false
        return try {
            val status = security!!.SecItemAdd(attributes, null)
            if (status != ERR_SUCCESS) {
                DesktopTrackLog.log("keychain store failed ($status) for $service/$account")
                false
            } else {
                true
            }
        } finally {
            cfRelease(attributes)
        }
    }

    fun deleteGenericPassword(service: String, account: String): Boolean {
        if (!available) return false
        val query = query(service, account) ?: return false
        return try {
            val status = security!!.SecItemDelete(query)
            status == ERR_SUCCESS || status == ERR_ITEM_NOT_FOUND || status == ERR_DUPLICATE_ITEM
        } finally {
            cfRelease(query)
        }
    }

    /**
     * Chromium's Safe Storage password. The account is usually the browser product name;
     * older installs sometimes leave it blank.
     */
    fun chromiumSafeStoragePassword(service: String, accountHint: String): ByteArray? =
        findGenericPassword(service, accountHint)
            ?: findGenericPassword(service, "")
            ?: findGenericPassword(service, accountHint.lowercase())

    private class Entry(val key: Pointer?, val value: Pointer?, val owned: Boolean)

    private fun query(
        service: String,
        account: String,
        extra: List<Entry> = emptyList(),
    ): Pointer? {
        val entries = buildList {
            add(Entry(secClass, secClassGenericPassword, owned = false))
            add(Entry(symbol("kSecAttrService"), cfString(service), owned = true))
            if (account.isNotEmpty()) {
                add(Entry(symbol("kSecAttrAccount"), cfString(account), owned = true))
            }
            addAll(extra)
        }
        return dictionary(entries)
    }

    private interface SecurityLib : com.sun.jna.Library {
        fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
        fun SecItemAdd(attributes: Pointer, result: Pointer?): Int
        fun SecItemDelete(query: Pointer): Int
    }

    private interface CoreFoundationLib : com.sun.jna.Library {
        fun CFStringCreateWithCString(alloc: Pointer?, cStr: String, encoding: Int): Pointer?
        fun CFDataCreate(alloc: Pointer?, bytes: Pointer, length: Long): Pointer?
        fun CFDictionaryCreate(
            alloc: Pointer?,
            keys: Pointer,
            values: Pointer,
            numValues: Long,
            keyCallbacks: Pointer?,
            valueCallbacks: Pointer?,
        ): Pointer?
        fun CFDataGetLength(data: Pointer): Long
        fun CFDataGetBytePtr(data: Pointer): Pointer?
        fun CFRelease(cf: Pointer)
        fun CFGetTypeID(cf: Pointer): Long
        fun CFDataGetTypeID(): Long
    }

    private val security: SecurityLib? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("Security", SecurityLib::class.java) }.getOrNull()
    }

    private val cf: CoreFoundationLib? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("CoreFoundation", CoreFoundationLib::class.java) }.getOrNull()
    }

    private val securityLib by lazy { NativeLibrary.getInstance("Security") }
    private val coreFoundation by lazy { NativeLibrary.getInstance("CoreFoundation") }

    private val secClass by lazy { symbol("kSecClass") }
    private val secClassGenericPassword by lazy { symbol("kSecClassGenericPassword") }

    private val keyCallbacks: Pointer? by lazy {
        runCatching { coreFoundation.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks") }.getOrNull()
    }

    private val valueCallbacks: Pointer? by lazy {
        runCatching { coreFoundation.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks") }.getOrNull()
    }

    private const val kCFStringEncodingUTF8 = 0x08000100

    /** Exported `CFTypeRef` / `CFStringRef` constant: the symbol holds a pointer. */
    private fun symbol(name: String): Pointer? = runCatching {
        val library = if (name.startsWith("kCF")) coreFoundation else securityLib
        library.getGlobalVariableAddress(name).getPointer(0)
    }.getOrNull()

    private fun cfBoolean(value: Boolean): Pointer? = symbol(if (value) "kCFBooleanTrue" else "kCFBooleanFalse")

    private fun cfString(value: String): Pointer? =
        cf?.CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)

    private fun cfData(bytes: ByteArray): Pointer? {
        if (bytes.isEmpty()) return cf?.CFDataCreate(null, Memory(1), 0)
        val memory = Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
        return cf?.CFDataCreate(null, memory, bytes.size.toLong())
    }

    private fun dictionary(entries: List<Entry>): Pointer? {
        val lib = cf ?: return null
        if (entries.any { it.key == null || it.value == null }) {
            entries.filter { it.owned }.forEach { cfRelease(it.value) }
            return null
        }
        val keyMem = Memory((entries.size * Native.POINTER_SIZE).toLong())
        val valueMem = Memory((entries.size * Native.POINTER_SIZE).toLong())
        entries.forEachIndexed { i, entry ->
            keyMem.setPointer((i * Native.POINTER_SIZE).toLong(), entry.key)
            valueMem.setPointer((i * Native.POINTER_SIZE).toLong(), entry.value)
        }
        val dictionary = lib.CFDictionaryCreate(
            null,
            keyMem,
            valueMem,
            entries.size.toLong(),
            keyCallbacks,
            valueCallbacks,
        )
        // Type callbacks retain. Release only the strings and data this call created.
        entries.filter { it.owned }.forEach { cfRelease(it.value) }
        return dictionary
    }

    private fun dataBytes(cfData: Pointer?): ByteArray? {
        if (cfData == null || cf == null) return null
        return try {
            if (cf!!.CFGetTypeID(cfData) != cf!!.CFDataGetTypeID()) return null
            val length = cf!!.CFDataGetLength(cfData).toInt()
            if (length <= 0) return ByteArray(0)
            val ptr = cf!!.CFDataGetBytePtr(cfData) ?: return null
            ptr.getByteArray(0, length)
        } finally {
            cfRelease(cfData)
        }
    }

    private fun cfRelease(pointer: Pointer?) {
        if (pointer != null) runCatching { cf?.CFRelease(pointer) }
    }
}
