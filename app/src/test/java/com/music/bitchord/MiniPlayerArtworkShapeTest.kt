package com.music.bitchord

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.miniPlayerArtShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MiniPlayerArtworkShapeTest {

    private val density = Density(density = 1f)
    private val artSize = Size(40f, 40f)

    @Test
    fun `default setting is false`() {
        assertFalse(AppSettings.roundMiniPlayerArtwork.value)
    }

    @Test
    fun `default shape has 8dp uniform corners`() {
        val shape = miniPlayerArtShape(rounded = false)
        assertEquals(8f, shape.topStart.toPx(artSize, density), 0.001f)
        assertEquals(8f, shape.topEnd.toPx(artSize, density), 0.001f)
        assertEquals(8f, shape.bottomStart.toPx(artSize, density), 0.001f)
        assertEquals(8f, shape.bottomEnd.toPx(artSize, density), 0.001f)
    }

    @Test
    fun `rounded shape has 20dp outer corners and 0dp inner corners`() {
        val shape = miniPlayerArtShape(rounded = true)
        assertEquals(20f, shape.topStart.toPx(artSize, density), 0.001f)
        assertEquals(0f, shape.topEnd.toPx(artSize, density), 0.001f)
        assertEquals(20f, shape.bottomStart.toPx(artSize, density), 0.001f)
        assertEquals(0f, shape.bottomEnd.toPx(artSize, density), 0.001f)
    }
}
