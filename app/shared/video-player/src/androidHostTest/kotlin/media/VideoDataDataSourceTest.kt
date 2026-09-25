/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

@file:AndroidxOptIn(UnstableApi::class)

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.SeekableInputMediaData
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class VideoDataDataSourceTest {
    private val mediaUri = mock<Uri> { on { toString() } doReturn "content://library/video" }
    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(), uri: Uri = mediaUri) =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    private class Input(override val size: Long = 10) : SeekableInput {
        override var position = 0L
        override val bytesRemaining get() = size - position
        var seekError: IOException? = null
        var closeCount = 0
        var readCount = 0
        override fun seekTo(position: Long) {
            seekError?.let { throw it }
            require(position in 0..size)
            this.position = position
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCount++
            if (position == size) return -1
            val count = minOf(length.toLong(), bytesRemaining).toInt()
            repeat(count) { buffer[offset + it] = (position + it).toByte() }
            position += count
            return count
        }
        override fun close() { closeCount++ }
    }

    /** Real BaseDataSource dispatch must pair each start/end with its non-null original DataSpec. */
    private class Listener : TransferListener {
        var initialized = 0
        var started = 0
        var ended = 0
        var bytes = 0
        var activeSpec: DataSpec? = null
        var endError: IOException? = null
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { initialized++ }
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            assertNull(activeSpec)
            activeSpec = dataSpec
            started++
        }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            assertSame(activeSpec, dataSpec)
            bytes += bytesTransferred
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            assertSame(activeSpec, dataSpec)
            activeSpec = null
            ended++
            endError?.let { throw it }
        }
    }

    private class Fixture {
        val input = Input()
        val media = mock<SeekableInputMediaData>()
        val listener = Listener()
        val source = VideoDataDataSource(media, input).apply { addTransferListener(listener) }
    }

    @Test fun `close before open and repeated close do not emit transfer end or close session input`() {
        val f = Fixture()
        f.source.close()
        f.source.open(spec())
        f.source.close()
        f.source.close()
        assertEquals(1, f.listener.started)
        assertEquals(1, f.listener.ended)
        assertNull(f.source.uri)
        assertEquals(0, f.input.closeCount)
        verify(f.media, never()).close()
    }

    @Test fun `failed seek has initialization but no transfer to end and can be retried`() {
        val f = Fixture()
        f.input.seekError = IOException("seek failed")
        assertFailsWith<IOException> { f.source.open(spec(4)) }
        f.source.close()
        f.source.close()
        assertEquals(1, f.listener.initialized)
        assertEquals(0, f.listener.started)
        assertEquals(0, f.listener.ended)
        f.input.seekError = null
        assertEquals(6L, f.source.open(spec(4)))
        f.source.close()
        assertEquals(1, f.listener.started)
        assertEquals(1, f.listener.ended)
    }

    @Test fun `every reopen seeks including zero and respects the requested range`() {
        val f = Fixture()
        val buffer = ByteArray(8)
        f.source.open(spec(6))
        assertEquals(4, f.source.read(buffer, 0, 8))
        f.source.close()
        assertEquals(3L, f.source.open(spec(0, 3)))
        assertEquals(3, f.source.read(buffer, 0, 8))
        assertContentEquals(byteArrayOf(0, 1, 2), buffer.copyOf(3))
        assertEquals(C.RESULT_END_OF_INPUT, f.source.read(buffer, 0, 8))
        assertEquals(0, f.source.read(buffer, 0, 0))
        f.source.close()
        assertEquals(2, f.listener.started)
        assertEquals(2, f.listener.ended)
        assertEquals(7, f.listener.bytes)
    }

    @Test fun `open exactly at end returns empty range rather than reading previous cursor`() {
        val f = Fixture()
        assertEquals(0L, f.source.open(spec(10)))
        assertEquals(C.RESULT_END_OF_INPUT, f.source.read(ByteArray(1), 0, 1))
        assertEquals(0, f.input.readCount)
        assertEquals(10L, f.input.position)
        f.source.close()
    }

    @Test fun `out of bounds open can be closed without a transfer end`() {
        val f = Fixture()
        assertFailsWith<DataSourceException> { f.source.open(spec(11)) }
        f.source.close()
        assertEquals(0, f.listener.started)
        assertEquals(0, f.listener.ended)
    }

    @Test fun `overlapping open is rejected without changing the active transfer`() {
        val f = Fixture()
        val first = spec(2)
        f.source.open(first)
        assertFailsWith<IllegalStateException> { f.source.open(spec(0)) }
        assertSame(first, f.listener.activeSpec)
        assertEquals(2L, f.input.position)
        f.source.close()
    }

    @Test fun `throwing transfer end listener cannot leave a transfer open for another close`() {
        val f = Fixture()
        f.source.open(spec())
        f.listener.endError = IOException("listener failed")
        assertFailsWith<IOException> { f.source.close() }
        f.source.close()
        assertEquals(1, f.listener.ended)
        f.listener.endError = null
        f.source.open(spec(1))
        f.source.close()
        assertEquals(2, f.listener.ended)
    }

    @Test fun `routing failure cleanup allows another open on shared session input`() {
        val f = Fixture()
        val routing = RoutingDataSource(mediaUri.toString(), DataSource.Factory { f.source }, DataSource.Factory { error("Unexpected fallback") })
        f.input.seekError = IOException("seek failed")
        assertFailsWith<IOException> { routing.open(spec(4)) }
        routing.close()
        routing.close()
        f.input.seekError = null
        routing.open(spec(0))
        routing.close()
        assertEquals(1, f.listener.ended)
        assertEquals(0, f.input.closeCount)
    }

    @Test fun `routing releases a delegate even when close fails and preserves listeners across routes`() {
        val f = Fixture()
        var mediaCreated = 0
        var fallbackCreated = 0
        val fallback = VideoDataDataSource(f.media, f.input)
        val failingClose = object : DataSource by f.source {
            override fun close() {
                f.source.close()
                throw IOException("delegate close failed")
            }
        }
        val routing = RoutingDataSource(mediaUri.toString(), DataSource.Factory { mediaCreated++; failingClose },
            DataSource.Factory { fallbackCreated++; fallback })
        val routedListener = Listener()
        routing.addTransferListener(routedListener)
        routing.open(spec(3))
        assertFailsWith<IOException> { routing.close() }
        routing.close()
        assertNull(routing.uri)
        val subtitleUri = mock<Uri> { on { toString() } doReturn "file://subtitle.ass" }
        routing.open(spec(uri = subtitleUri))
        routing.close()
        assertEquals(1, mediaCreated)
        assertEquals(1, fallbackCreated)
        assertEquals(2, routedListener.started)
        assertEquals(2, routedListener.ended)
    }
}
