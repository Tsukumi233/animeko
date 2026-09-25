/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.platform.Context
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext

actual fun createLocalResourceAccess(context: Context): LocalResourceAccess =
    AndroidLocalResourceAccess(context.applicationContext.contentResolver)

internal class AndroidLocalResourceAccess(private val resolver: ContentResolver) : LocalResourceAccess {
    private val system = SystemLocalResourceAccess()
    private fun isDocument(uri: String) = Uri.parse(uri).scheme == ContentResolver.SCHEME_CONTENT

    private fun documentUri(uri: Uri): Uri =
        if (DocumentsContract.isTreeUri(uri) && !uri.pathSegments.contains("document")) {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        } else uri

    private fun grantUri(uri: Uri): Uri = if (DocumentsContract.isTreeUri(uri)) {
        DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), DocumentsContract.getTreeDocumentId(uri))
    } else uri

    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    private fun Cursor.entry(uri: Uri): LocalResourceEntry = LocalResourceEntry(
        uri.toString(), getString(1) ?: uri.lastPathSegment.orEmpty(),
        getString(2) == Document.MIME_TYPE_DIR,
        if (isNull(3)) null else getLong(3), if (isNull(4)) null else getLong(4),
    )

    override fun relativePathSegments(rootUri: String, resourceUri: String): List<String>? {
        if (!isDocument(rootUri) && !isDocument(resourceUri)) return system.relativePathSegments(rootUri, resourceUri)
        if (!isDocument(rootUri) || !isDocument(resourceUri)) return null
        val root = Uri.parse(rootUri)
        val resource = Uri.parse(resourceUri)
        // ExternalStorageProvider document IDs consist of a volume identifier and a relative filesystem path.
        if (root.authority != "com.android.externalstorage.documents" || resource.authority != root.authority) return null
        val rootId = DocumentsContract.getDocumentId(documentUri(root)).trimEnd('/')
        val resourceId = DocumentsContract.getDocumentId(documentUri(resource))
        return externalStorageRelativePathSegments(rootId, resourceId)
    }

    override suspend fun stat(uri: String): LocalResourceEntry {
        if (!isDocument(uri)) return system.stat(uri)
        return withContext(Dispatchers.IO) {
            val document = documentUri(Uri.parse(uri))
            resolver.query(document, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw FileNotFoundException("Document does not exist")
                cursor.entry(document)
            } ?: throw IOException("Document provider did not return metadata")
        }
    }

    override suspend fun list(directoryUri: String): List<LocalResourceEntry> {
        if (!isDocument(directoryUri)) return system.list(directoryUri)
        require(stat(directoryUri).isDirectory) { "Resource is not a directory" }
        return withContext(Dispatchers.IO) {
            val directory = documentUri(Uri.parse(directoryUri))
            require(DocumentsContract.isTreeUri(directory)) { "Directory browsing requires a tree grant" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory,
                DocumentsContract.getDocumentId(directory))
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        ensureActive()
                        add(cursor.entry(DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0))))
                    }
                }.sortedWith(compareByDescending<LocalResourceEntry> { it.isDirectory }.thenBy { it.name })
            } ?: throw IOException("Document provider did not return children")
        }
    }

    override suspend fun persistReadPermission(uri: String) {
        if (!isDocument(uri)) return system.persistReadPermission(uri)
        withContext(Dispatchers.IO) {
            resolver.takePersistableUriPermission(grantUri(Uri.parse(uri)), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override suspend fun releaseReadPermission(uri: String) {
        if (!isDocument(uri)) return system.releaseReadPermission(uri)
        withContext(Dispatchers.IO) {
            val parsed = grantUri(Uri.parse(uri))
            if (resolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission }) {
                resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    override suspend fun createMediaDataProvider(uri: String): MediaDataProvider<*> {
        if (!isDocument(uri)) return system.createMediaDataProvider(uri)
        require(!stat(uri).isDirectory) { "Cannot play a directory" }
        return DocumentMediaDataProvider(resolver, documentUri(Uri.parse(uri)))
    }
}

@OptIn(ExperimentalMediampApi::class)
private class DocumentMediaDataProvider(
    private val resolver: ContentResolver,
    private val document: Uri,
) : MediaDataProvider<SeekableInputMediaData> {
    override val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY

    private fun openInput(): ChannelSeekableInput {
        val descriptor = resolver.openFileDescriptor(document, "r")
            ?: throw FileNotFoundException("Document cannot be opened")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        return try {
            // Some providers return pipes. Report this instead of copying a whole video eagerly.
            stream.channel.position(0)
            stream.channel.size()
            ChannelSeekableInput(stream.channel) { stream.close() }
        } catch (failure: Throwable) {
            stream.close()
            throw failure
        }
    }

    override suspend fun open(scopeForCleanup: CoroutineScope): SeekableInputMediaData = withContext(Dispatchers.IO) {
        val length = openInput().use { it.size }
        object : SeekableInputMediaData {
            override val uri: String = document.toString()
            override val extraFiles: MediaExtraFiles = this@DocumentMediaDataProvider.extraFiles
            override val options: List<String> = emptyList()
            private val inputs = mutableSetOf<SeekableInput>()
            private var closed = false
            override fun fileLength(): Long = length
            override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
                withContext(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    synchronized(inputs) {
                        check(!closed) { "Document media has been closed" }
                        val input = openInput()
                        val owned = object : SeekableInput by input {
                            override fun close() = synchronized(inputs) {
                                input.close()
                                inputs.remove(this)
                                Unit
                            }
                        }
                        inputs.add(owned)
                        owned
                    }
                }

            override fun close() {
                synchronized(inputs) {
                    closed = true
                    val pending = inputs.toList()
                    inputs.clear()
                    var failure: Throwable? = null
                    pending.forEach { input ->
                        try {
                            input.close()
                        } catch (error: Throwable) {
                            if (failure == null) failure = error else failure!!.addSuppressed(error)
                        }
                    }
                    failure?.let { throw it }
                }
            }
        }
    }
}
