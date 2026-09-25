/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.media.download.capability.DownloadByteReader
import org.openani.mediamp.io.SeekableInput

/** Stored separately from source arguments; never included in exported source configuration. */
@Serializable
data class FileServiceCredentials(val username: String, val password: String, val domain: String = "") {
    override fun toString(): String = "FileServiceCredentials(redacted)"
}

fun interface FileServiceCredentialProvider {
    suspend fun get(sourceId: String): FileServiceCredentials?
}

@Serializable
data class FileServiceEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long?,
    val modifiedTimeMillis: Long?,
    val contentIdentity: String? = null,
    val mimeType: String? = null,
)

/** Paths are decoded, relative to the configured root, and never contain traversal components. */
internal fun checkedRelativePath(path: String): String {
    require(!path.startsWith('/') && !path.endsWith('/') && '\\' !in path && '\u0000' !in path) { "Invalid relative path" }
    require(path.isEmpty() || path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid path component" }
    return path
}

interface FileServiceAccess : AutoCloseable {
    suspend fun list(path: String, credentials: FileServiceCredentials): List<FileServiceEntry>
    suspend fun stat(path: String, credentials: FileServiceCredentials): FileServiceEntry
}

interface SmbFileServiceAccess : FileServiceAccess {
    suspend fun open(path: String, credentials: FileServiceCredentials): FileServiceOpenFile
}

interface FileServiceOpenFile : DownloadByteReader {
    val size: Long
    val contentIdentity: String?
    /** The returned input owns this file handle and closes it when disposed. */
    fun asSeekableInput(): SeekableInput
}

expect fun createSmbFileServiceAccess(host: String, port: Int, share: String, root: String): SmbFileServiceAccess
