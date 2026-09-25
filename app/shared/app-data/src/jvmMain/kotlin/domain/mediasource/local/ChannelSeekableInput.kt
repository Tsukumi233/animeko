/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import org.openani.mediamp.io.SeekableInput
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Owns its channel and accepts 64-bit offsets without narrowing them. */
internal class ChannelSeekableInput(
    private val channel: FileChannel,
    private val closeOwner: () -> Unit = { channel.close() },
) : SeekableInput {
    override val size: Long get() = channel.size()
    override val position: Long get() = channel.position()
    override val bytesRemaining: Long get() = (size - position).coerceAtLeast(0)

    override fun seekTo(position: Long) {
        require(position in 0..size) { "Seek position outside file" }
        channel.position(position)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length))

    override fun close() = closeOwner()
}
