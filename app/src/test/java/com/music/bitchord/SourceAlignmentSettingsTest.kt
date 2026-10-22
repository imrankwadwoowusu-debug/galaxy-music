package com.music.bitchord

import android.content.SharedPreferences
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class SourceAlignmentSettingsTest {

    private val preferencesMap = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        preferencesMap.clear()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    preferencesMap[args[0] as String] = args[1] as Boolean
                    proxy
                }
                "apply", "commit" -> null
                else -> if (method.returnType == SharedPreferences.Editor::class.java) proxy else null
            }
        } as SharedPreferences.Editor

        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> (preferencesMap[args[0] as String] as? Boolean) ?: (args[1] as Boolean)
                "contains" -> preferencesMap.containsKey(args[0] as String)
                else -> if (method.name.startsWith("get") && args != null && args.size == 2) args[1]
                else if (method.name == "edit") editor
                else null
            }
        } as SharedPreferences

        val field = AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(AppSettings, prefs)

        // readAll() also reads the encrypted auth store; give it one backed by the same map.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val authStore = unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, AuthStore::class.java) as AuthStore
        AuthStore::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
            set(authStore, prefs)
        }
        AppSettings::class.java.getDeclaredField("authStore").apply {
            isAccessible = true
            set(AppSettings, authStore)
        }
    }

    private fun readAll() {
        val method = AppSettings::class.java.getDeclaredMethod("readAll")
        method.isAccessible = true
        method.invoke(AppSettings)
    }

    @Test
    fun `smartSourceAlignment defaults to off when nothing is stored`() {
        readAll()
        assertFalse(AppSettings.smartSourceAlignment.value)
    }

    @Test
    fun `smartSourceAlignment reads a stored value`() {
        preferencesMap["smart_source_alignment"] = true
        readAll()
        assertTrue(AppSettings.smartSourceAlignment.value)
    }

    @Test
    fun `sourceAlignmentEnabled requires the smartVersionAlignment parent`() {
        preferencesMap["smart_source_alignment"] = true
        preferencesMap["smart_version_alignment"] = false
        readAll()
        assertFalse(AppSettings.sourceAlignmentEnabled)

        preferencesMap["smart_version_alignment"] = true
        readAll()
        assertTrue(AppSettings.sourceAlignmentEnabled)
    }

    @Test
    fun `setSmartSourceAlignment updates flow and persists to SharedPreferences`() {
        AppSettings.setSmartSourceAlignment(true)
        assertTrue(AppSettings.smartSourceAlignment.value)
        assertEquals(true, preferencesMap["smart_source_alignment"])

        AppSettings.setSmartSourceAlignment(false)
        assertFalse(AppSettings.smartSourceAlignment.value)
        assertEquals(false, preferencesMap["smart_source_alignment"])
    }
}
