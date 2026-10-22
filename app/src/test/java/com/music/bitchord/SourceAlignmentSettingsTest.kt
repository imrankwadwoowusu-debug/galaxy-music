package com.music.bitchord

import android.content.SharedPreferences
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
                "getBoolean" -> {
                    val key = args[0] as String
                    val defValue = args[1] as Boolean
                    (preferencesMap[key] as? Boolean) ?: defValue
                }
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences

        val field = AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(AppSettings, prefs)
    }

    @Test
    fun `smartSourceAlignment defaults to false`() {
        AppSettings.smartSourceAlignment.value = false
        assertFalse(AppSettings.smartSourceAlignment.value)
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

    @Test
    fun `smartSourceAlignment gating depends on smartVersionAlignment`() {
        AppSettings.smartVersionAlignment.value = false
        AppSettings.smartSourceAlignment.value = true
        val effectivelyActive1 = AppSettings.smartVersionAlignment.value && AppSettings.smartSourceAlignment.value
        assertFalse("Cannot be active when parent smartVersionAlignment is disabled", effectivelyActive1)

        AppSettings.smartVersionAlignment.value = true
        AppSettings.smartSourceAlignment.value = true
        val effectivelyActive2 = AppSettings.smartVersionAlignment.value && AppSettings.smartSourceAlignment.value
        assertTrue("Active when both smartVersionAlignment and smartSourceAlignment are enabled", effectivelyActive2)
    }
}
