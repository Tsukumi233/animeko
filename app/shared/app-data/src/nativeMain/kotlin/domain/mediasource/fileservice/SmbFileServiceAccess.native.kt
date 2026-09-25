/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

actual fun createSmbFileServiceAccess(host: String, port: Int, share: String, root: String): SmbFileServiceAccess =
    object : SmbFileServiceAccess {
        override suspend fun list(path: String, credentials: FileServiceCredentials): List<FileServiceEntry> = unsupported()
        override suspend fun stat(path: String, credentials: FileServiceCredentials): FileServiceEntry = unsupported()
        override suspend fun open(path: String, credentials: FileServiceCredentials): FileServiceOpenFile = unsupported()
        override fun close() = Unit
        private fun unsupported(): Nothing = throw UnsupportedOperationException("SMB is supported on Windows and Android")
    }

internal actual fun parseFileServiceHttpDate(value: String): Long? = null
