/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.openani.mediamp.io.SeekableInput
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

actual fun createSmbFileServiceAccess(host: String, port: Int, share: String, root: String): SmbFileServiceAccess =
    SmbjFileServiceAccess(host, port, share, root)

internal actual fun parseFileServiceHttpDate(value: String): Long? =
    runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

private class SmbjFileServiceAccess(
    private val host: String,
    private val port: Int,
    private val shareName: String,
    root: String,
) : SmbFileServiceAccess {
    private val root = checkedRelativePath(root)
    private val connections = Collections.synchronizedSet(mutableSetOf<SmbConnection>())
    private val closed = AtomicBoolean(false)

    init {
        require(host.isNotBlank() && port in 1..65535)
        require(shareName.isNotBlank() && shareName.none { it == '/' || it == '\\' || it == '\u0000' })
    }

    private fun fullPath(path: String): String = listOf(root, checkedRelativePath(path))
        .filter { it.isNotEmpty() }.joinToString("/").replace('/', '\\')

    private fun connect(credentials: FileServiceCredentials): SmbConnection {
        check(!closed.get())
        val client = SMBClient(SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build())
        try {
            val connection = client.connect(host, port)
            val auth = if (credentials.username.isEmpty()) AuthenticationContext.anonymous() else
                AuthenticationContext(credentials.username, credentials.password.toCharArray(), credentials.domain)
            val share = connection.authenticate(auth).connectShare(shareName) as? DiskShare
                ?: throw FileServiceAccessException("The SMB share is not a disk share")
            val result = SmbConnection(client, connection, share) { connections.remove(it) }
            connections.add(result)
            if (closed.get()) {
                result.close()
                error("SMB source is closed")
            }
            return result
        } catch (e: Throwable) {
            client.close()
            throw e
        }
    }

    override suspend fun list(path: String, credentials: FileServiceCredentials): List<FileServiceEntry> =
        runInterruptible(Dispatchers.IO) {
            connect(credentials).use { connection ->
                connection.share.list(fullPath(path)).filter { it.fileName != "." && it.fileName != ".." }.map {
                    val relative = listOf(path, it.fileName).filter(String::isNotEmpty).joinToString("/")
                    checkedRelativePath(relative)
                    FileServiceEntry(
                        relative, it.fileName,
                        it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                        it.endOfFile, it.lastWriteTime.epochMillis(),
                        contentIdentity = smbVersion(it.fileId, it.changeTime, it.lastWriteTime, it.endOfFile),
                    )
                }
            }
        }

    override suspend fun stat(path: String, credentials: FileServiceCredentials): FileServiceEntry =
        runInterruptible(Dispatchers.IO) {
            connect(credentials).use { connection ->
                val info = connection.share.getFileInformation(fullPath(path))
                FileServiceEntry(path, path.substringAfterLast('/'), info.standardInformation.isDirectory,
                    info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.epochMillis(), info.version())
            }
        }

    override suspend fun open(path: String, credentials: FileServiceCredentials): FileServiceOpenFile {
        var opened: SmbOpenFile? = null
        try {
            return runInterruptible(Dispatchers.IO) {
                val connection = connect(credentials)
                try {
                    val file = connection.share.openFile(
                        fullPath(path), EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.noneOf(FileAttributes::class.java), SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                    )
                    SmbOpenFile(connection, file).also { opened = it }
                } catch (e: Throwable) {
                    connection.close()
                    throw e
                }
            }
        } catch (e: Throwable) {
            opened?.close()
            throw e
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(connections) { connections.toList() }.forEach { it.close() }
        }
    }
}

private class SmbConnection(
    private val client: SMBClient,
    private val connection: Connection,
    val share: DiskShare,
    private val onClosed: (SmbConnection) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { connection.close(true) } finally {
                try { client.close() } finally { onClosed(this) }
            }
        }
    }
}

private class SmbOpenFile(private val connection: SmbConnection, private val file: File) : FileServiceOpenFile {
    private val information = file.fileInformation
    override val size = information.standardInformation.endOfFile
    override val contentIdentity = information.version()
    private val closed = AtomicBoolean(false)

    private fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        check(!closed.get())
        require(offset >= 0 && bufferOffset >= 0 && length >= 0 && bufferOffset <= buffer.size - length)
        if (length == 0) return 0
        if (offset >= size) return -1
        return file.read(buffer, offset, bufferOffset, minOf(length.toLong(), size - offset).toInt())
    }

    override suspend fun readAt(offset: Long, buffer: ByteArray, length: Int): Int = try {
        runInterruptible(Dispatchers.IO) { read(offset, buffer, 0, length) }
    } catch (e: Throwable) {
        close()
        throw e
    }

    override fun asSeekableInput(): SeekableInput = object : SeekableInput {
        override var position: Long = 0
            private set
        override val size: Long get() = this@SmbOpenFile.size
        override val bytesRemaining: Long get() = size - position
        override fun seekTo(position: Long) { require(position in 0..size); this.position = position }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            this@SmbOpenFile.read(position, buffer, offset, length).also { if (it > 0) position += it }
        override fun close() = this@SmbOpenFile.close()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Each input owns its connection. Disconnect releases server handles and interrupts reads.
            connection.close()
        }
    }
}

private fun FileTime.epochMillis(): Long = toEpochMillis()

private fun FileAllInformation.version(): String? = smbVersion(
    internalInformation.indexNumber, basicInformation.changeTime, basicInformation.lastWriteTime, standardInformation.endOfFile,
)

/** Server-reported version, not a cryptographic digest; every reopened handle is checked before reuse. */
private fun smbVersion(id: Long, changed: FileTime, written: FileTime, size: Long): String? {
    val changeTime = changed.toEpoch(TimeUnit.NANOSECONDS)
    val writeTime = written.toEpoch(TimeUnit.NANOSECONDS)
    return if (id != 0L && changeTime > 0 && writeTime > 0 && size >= 0) "smb:$id:$changeTime:$writeTime:$size" else null
}
