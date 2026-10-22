package com.music.bitchord.playback.smart

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@UnstableApi
class StreamAudioSourceTest {

    private class FakeDataSource(private val data: ByteArray) : DataSource {
        var isOpen = false
        var currentPosition = 0L
        var openCount = 0
        var closeCount = 0
        var throwOnOpen = false
        var throwOnRead = false

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            if (throwOnOpen) throw IOException("Simulated open failure")
            isOpen = true
            openCount++
            currentPosition = dataSpec.position
            if (currentPosition >= data.size) {
                return 0L
            }
            val remaining = data.size - currentPosition
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, remaining)
            } else {
                remaining
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (throwOnRead) throw IOException("Simulated read failure")
            if (!isOpen) throw IOException("DataSource not open")
            if (currentPosition >= data.size) return C.RESULT_END_OF_INPUT
            val bytesToRead = minOf(length, (data.size - currentPosition).toInt())
            System.arraycopy(data, currentPosition.toInt(), buffer, offset, bytesToRead)
            currentPosition += bytesToRead
            return bytesToRead
        }

        override fun getUri(): Uri? = null

        override fun close() {
            if (isOpen) {
                isOpen = false
                closeCount++
            }
        }
    }

    private val testBytes = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    private val testUri: Uri = android.net.TestUri()

    @Test
    fun `sequential reads keep underlying datasource open`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)

        val buffer = ByteArray(4)
        val read1 = source.readAt(0, buffer, 0, 4)
        assertEquals(4, read1)
        assertEquals(10.toByte(), buffer[0])
        assertEquals(40.toByte(), buffer[3])
        assertEquals(1, fakeDs.openCount)
        assertTrue(fakeDs.isOpen)

        val read2 = source.readAt(4, buffer, 0, 4)
        assertEquals(4, read2)
        assertEquals(50.toByte(), buffer[0])
        assertEquals(80.toByte(), buffer[3])
        // Should NOT reopen for contiguous sequential read
        assertEquals(1, fakeDs.openCount)
        assertTrue(fakeDs.isOpen)

        source.close()
        assertFalse(fakeDs.isOpen)
        assertEquals(1, fakeDs.closeCount)
    }

    @Test
    fun `non-sequential seek triggers reopen at new position`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)

        val buffer = ByteArray(2)
        source.readAt(0, buffer, 0, 2)
        assertEquals(1, fakeDs.openCount)

        // Non-sequential jump to index 6
        val read = source.readAt(6, buffer, 0, 2)
        assertEquals(2, read)
        assertEquals(70.toByte(), buffer[0])
        assertEquals(80.toByte(), buffer[1])
        assertEquals(2, fakeDs.openCount)
        assertEquals(1, fakeDs.closeCount)

        source.close()
    }

    @Test
    fun `zero size returns zero without reading`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)
        val buffer = ByteArray(4)

        val read = source.readAt(0, buffer, 0, 0)
        assertEquals(0, read)
        assertEquals(0, fakeDs.openCount)

        source.close()
    }

    @Test
    fun `negative position returns EOF`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)
        val buffer = ByteArray(4)

        val read = source.readAt(-1, buffer, 0, 4)
        assertEquals(-1, read)

        source.close()
    }

    @Test
    fun `read past EOF returns -1`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)
        val buffer = ByteArray(4)

        val read = source.readAt(100, buffer, 0, 4)
        assertEquals(-1, read)

        source.close()
    }

    @Test
    fun `open failure handles gracefully and returns -1`() {
        val fakeDs = FakeDataSource(testBytes).apply { throwOnOpen = true }
        val source = StreamAudioSource(fakeDs, testUri)
        val buffer = ByteArray(4)

        val read = source.readAt(0, buffer, 0, 4)
        assertEquals(-1, read)

        source.close()
    }

    @Test
    fun `read failure handles gracefully, closes upstream and returns -1`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)
        val buffer = ByteArray(4)

        source.readAt(0, buffer, 0, 2)
        fakeDs.throwOnRead = true

        val read = source.readAt(2, buffer, 0, 2)
        assertEquals(-1, read)
        assertFalse(fakeDs.isOpen)

        source.close()
    }

    @Test
    fun `getSize returns length from datasource`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)

        val size = source.size
        assertEquals(10L, size)

        source.close()
    }

    @Test
    fun `invalid buffer bounds returns -1`() {
        val fakeDs = FakeDataSource(testBytes)
        val source = StreamAudioSource(fakeDs, testUri)
        val buffer = ByteArray(4)

        assertEquals(-1, source.readAt(0, buffer, -1, 2))
        assertEquals(-1, source.readAt(0, buffer, 0, 5))
        assertEquals(-1, source.readAt(0, buffer, 3, 2))

        source.close()
    }
}
