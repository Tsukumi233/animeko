/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:AndroidxOptIn(UnstableApi::class)

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.SeekableInputMediaData
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

/**
 * Adapts a session-owned seekable input to ExoPlayer transfer lifecycles.
 *
 * Closing a transfer preserves [videoData] and its input for subsequent range opens.
 */
@AndroidxOptIn(UnstableApi::class)
class VideoDataDataSource(
    private val videoData: SeekableInputMediaData,
    private val file: SeekableInput,
) : BaseDataSource(true) {
    private companion object {
        @JvmStatic
        private val logger = logger<VideoDataDataSource>()
        private const val ENABLE_READ_LOG = false
        private const val ENABLE_TRACE_LOG = false
    }

    private var uri: Uri? = null

    private var opened = false
    private var bytesRemaining = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // 性能提示: 这个函数会被非常频繁调用 (一个 byte 一次), 速度会直接影响视频首帧延迟

        if (length == 0) return 0
        check(opened) { "Data source is not open" }
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val readLength = minOf(length.toLong(), bytesRemaining).toInt()

        if (ENABLE_READ_LOG) { // const val, optimized out
            logger.warn { "VideoDataDataSource read: offset=$offset, length=$length" }
        }

        val bytesRead = if (ENABLE_READ_LOG) {
            val (value, time) = measureTimedValue {
                file.read(buffer, offset, readLength)
            }
            if (time > 100.milliseconds) {
                logger.warn { "VideoDataDataSource slow read: read $offset for length $length took $time" }
            }
            value
        } else {
            file.read(buffer, offset, readLength)
        }
        if (bytesRead == -1) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        bytesRemaining -= bytesRead
        if (bytesRead > 0) bytesTransferred(bytesRead)
        return bytesRead
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        if (ENABLE_TRACE_LOG) logger.info { "Opening dataSpec, offset=${dataSpec.position}, length=${dataSpec.length}, videoData=$videoData" }

        check(!opened) { "Data source is already open" }
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val size = file.size
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        // Every open establishes its own absolute position, including zero and the end of the file.
        file.seekTo(dataSpec.position)
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            file.bytesRemaining
        } else {
            minOf(dataSpec.length, file.bytesRemaining)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (ENABLE_TRACE_LOG) logger.info { "Closing VideoDataDataSource" }
        uri = null
        bytesRemaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}