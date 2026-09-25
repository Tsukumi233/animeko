/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import kotlin.coroutines.CoroutineContext

internal class FileServiceMediaDataProvider(
    private val name: String,
    private val openFile: suspend () -> FileServiceOpenFile,
) : MediaDataProvider<SeekableInputMediaData> {
    override val extraFiles = MediaExtraFiles.EMPTY

    override suspend fun open(scopeForCleanup: CoroutineScope): SeekableInputMediaData = object : SeekableInputMediaData {
        private val lock = SynchronizedObject()
        private val inputs = mutableSetOf<SeekableInput>()
        private var closed = false
        override val uri = "fileservice://video"
        override val extraFiles = MediaExtraFiles.EMPTY
        override val options = emptyList<String>()
        override fun fileLength(): Long? = null

        override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
            val file = openFile()
            val input = try {
                currentCoroutineContext().ensureActive()
                file.asSeekableInput()
            } catch (e: Throwable) {
                file.close()
                throw e
            }
            synchronized(lock) {
                if (closed) {
                    input.close()
                    error("Media data is closed")
                }
                inputs += input
            }
            return object : SeekableInput by input {
                override fun close() {
                    synchronized(lock) { inputs.remove(input) }
                    input.close()
                }
            }
        }

        override fun close() {
            val remaining = synchronized(lock) {
                closed = true
                inputs.toList().also { inputs.clear() }
            }
            remaining.forEach { it.close() }
        }

        override fun toString(): String = "FileServiceMediaData(name=$name)"
    }
}
