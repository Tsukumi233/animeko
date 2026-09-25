/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.player.data.SystemFileMediaDataProvider
import org.openani.mediamp.source.MediaExtraFiles
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal class SystemLocalResourceAccess : LocalResourceAccess {
    private fun file(uri: String): File {
        val file = if (uri.startsWith("file:", ignoreCase = true)) File(URI(uri)) else File(uri)
        require(file.isAbsolute) { "A local resource requires an absolute path or file URI" }
        return file.absoluteFile.normalize()
    }

    private fun entry(file: File): LocalResourceEntry {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(!attributes.isSymbolicLink) { "Symbolic links are not indexed as local resources" }
        return LocalResourceEntry(file.toURI().toASCIIString(), file.name, attributes.isDirectory,
            if (attributes.isRegularFile) attributes.size() else null, attributes.lastModifiedTime().toMillis())
    }

    override fun relativePathSegments(rootUri: String, resourceUri: String): List<String>? {
        val root = file(rootUri).toPath()
        val resource = file(resourceUri).toPath()
        if (!resource.startsWith(root)) return null
        if (root == resource) return emptyList()
        return root.relativize(resource).map { it.toString() }
    }

    override suspend fun stat(uri: String): LocalResourceEntry = withContext(Dispatchers.IO) { entry(file(uri)) }

    override suspend fun list(directoryUri: String): List<LocalResourceEntry> = withContext(Dispatchers.IO) {
        val directory = file(directoryUri)
        require(entry(directory).isDirectory) { "Resource is not a directory" }
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            stream.mapNotNull {
                ensureActive()
                if (Files.isSymbolicLink(it)) null else entry(it.toFile())
            }.sortedWith(compareByDescending<LocalResourceEntry> { it.isDirectory }.thenBy { it.name })
        }
    }

    override suspend fun persistReadPermission(uri: String) { file(uri) }
    override suspend fun releaseReadPermission(uri: String) { file(uri) }

    override suspend fun createMediaDataProvider(uri: String): MediaDataProvider<*> {
        require(!stat(uri).isDirectory) { "Cannot play a directory" }
        return SystemFileMediaDataProvider(Path(file(uri).path), MediaExtraFiles.EMPTY, null)
    }
}
