/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.platform.Context

data class LocalResourceEntry(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedMillis: Long?,
)

/** Read-only access. A missing document or revoked grant is an error, never an empty directory. */
interface LocalResourceAccess {
    suspend fun stat(uri: String): LocalResourceEntry
    suspend fun list(directoryUri: String): List<LocalResourceEntry>

    /** Lexical relative path when the provider defines path-based identities; null for opaque or unrelated IDs. */
    fun relativePathSegments(rootUri: String, resourceUri: String): List<String>? = null

    /** Retain the URI returned by the picker (the tree root, rather than a derived child URI). */
    suspend fun persistReadPermission(uri: String)

    /** Release an original picker grant only after no saved resources depend on that grant. */
    suspend fun releaseReadPermission(uri: String)

    /** Opens read-only inputs lazily. The caller owns and closes the resulting media data and inputs. */
    suspend fun createMediaDataProvider(uri: String): MediaDataProvider<*>
}

expect fun createLocalResourceAccess(context: Context): LocalResourceAccess

/** ExternalStorageProvider's volume:path document ID format; other providers use opaque identities. */
internal fun externalStorageRelativePathSegments(rootId: String, resourceId: String): List<String>? {
    if (':' !in rootId || ':' !in resourceId) return null
    if (rootId == resourceId) return emptyList()
    val prefix = if (rootId.endsWith(':')) rootId else rootId.trimEnd('/') + "/"
    if (!resourceId.startsWith(prefix)) return null
    return resourceId.removePrefix(prefix).split('/').takeIf { parts ->
        parts.all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
