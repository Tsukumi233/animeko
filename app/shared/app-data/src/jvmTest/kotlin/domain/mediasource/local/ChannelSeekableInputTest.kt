/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChannelSeekableInputTest {
    @Test
    fun `seek uses long offsets and close releases descriptor`() {
        val directory = Files.createTempDirectory("ani-input")
        val path = directory.resolve("sparse.bin")
        try {
            val offset = Int.MAX_VALUE.toLong() + 33
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use {
                it.position(offset)
                it.write(ByteBuffer.wrap(byteArrayOf(42)))
            }
            val file = RandomAccessFile(path.toFile(), "r")
            val input = ChannelSeekableInput(file.channel) { file.close() }
            input.use {
                assertEquals(offset + 1, it.size)
                it.seekTo(offset)
                assertEquals(1L, it.bytesRemaining)
                val byte = ByteArray(1)
                assertEquals(1, it.read(byte))
                assertEquals(42, byte[0].toInt())
                assertEquals(-1, it.read(byte))
                it.seekTo(0)
                assertEquals(0L, it.position)
                assertFailsWith<IllegalArgumentException> { it.seekTo(-1) }
            }
            input.close()
            assertFailsWith<ClosedChannelException> { input.read(ByteArray(1)) }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
