/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.searchFiles
import me.him188.ani.torrent.offline.OfflineDownloadRejectedException
import kotlin.time.Instant

data class PikPakDriveEntry(
    val fileId: String,
    val parentId: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val mimeType: String?,
    val modifiedAt: Instant?,
    val contentIdentity: String?,
)

data class PikPakDrivePage(val entries: List<PikPakDriveEntry>, val nextPageToken: String?)

/** Signed request data is ephemeral; persist the account scope and file ID instead. */
data class PikPakFileAccess(
    val url: String,
    val headers: Map<String, String>,
    val expiresAt: Instant?,
    val fileId: String,
    val fileName: String,
    val fileSize: Long?,
    /** Content hash when available. A file ID or size alone cannot establish safe resumability. */
    val contentIdentity: String?,
)

/** Read-only access to existing files; no offline jobs or remote cleanup are invoked here. */
class PikPakDriveAccess(private val accounts: PikPakAccountProvider) {
    suspend fun list(
        accountScope: String,
        parentId: String = "",
        pageToken: String? = null,
        pageSize: Int = 100,
    ): PikPakDrivePage {
        require(pageSize in 1..1000)
        return accounts.withClient(accountScope) { _, client ->
            val page = client.listDriveFilesPage(parentId, pageSize, pageToken.orEmpty())
            PikPakDrivePage(
                page.files.filterNot { it.trashed }.map { it.toDriveEntry() },
                page.nextPageToken.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * SDK 0.4.3 reads all pages of this folder and filters names case-insensitively on the client.
     * It does not search descendants or the whole account, and exposes no search page token.
     */
    suspend fun search(accountScope: String, keyword: String, parentId: String = ""): List<PikPakDriveEntry> {
        require(keyword.isNotEmpty())
        return accounts.withClient(accountScope) { _, client ->
            client.searchFiles(keyword, parentId, false).filterNot { it.trashed }.map { it.toDriveEntry() }
        }
    }

    /** Fetches a fresh signed link each time, for both initial playback and download request refresh. */
    suspend fun resolve(accountScope: String, fileId: String): PikPakFileAccess {
        require(fileId.isNotEmpty())
        return accounts.withClient(accountScope) { _, client ->
            val file = client.getFile(fileId)
            if (file.id != fileId || file.kind == FileKind.FOLDER || file.trashed) {
                throw OfflineDownloadRejectedException("PikPak file is unavailable")
            }
            file.toFileAccess()
        }
    }
}

private fun FileStat.toDriveEntry() = PikPakDriveEntry(
    fileId = id,
    parentId = parentId,
    name = name,
    isDirectory = isFolder,
    size = size.toLongOrNull()?.takeIf { it >= 0 },
    mimeType = mimeType.takeIf { it.isNotEmpty() },
    modifiedAt = modifiedTime.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
    contentIdentity = contentIdentity(hash, md5Checksum),
)

internal fun FileDetail.toFileAccess(): PikPakFileAccess {
    val resolved = buildResolvedMedia(this)
    return PikPakFileAccess(
        url = resolved.streamUrl,
        headers = mapOf("Accept" to "application/octet-stream"),
        expiresAt = if (downloadUrl.isNullOrEmpty()) null else resolved.expiresAt,
        fileId = id,
        fileName = name,
        fileSize = size.toLongOrNull()?.takeIf { it >= 0 },
        contentIdentity = contentIdentity(hash, md5Checksum),
    )
}

private fun contentIdentity(hash: String, md5: String): String? = when {
    hash.isNotEmpty() -> "hash:$hash"
    md5.isNotEmpty() -> "md5:$md5"
    else -> null
}
