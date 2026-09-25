/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.source.SeekableInputMediaData
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalResourceDocumentTest {
    @Test
    fun documentTreeAndSeekableInput() = runTest {
        val access = AndroidLocalResourceAccess(InstrumentationRegistry.getInstrumentation().context.contentResolver)
        val root = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        assertTrue(access.stat(root.toString()).isDirectory)
        val entry = access.list(root.toString()).single()
        assertEquals("剧集 01 %.mkv", entry.name)
        val provider = access.createMediaDataProvider(entry.uri)
        val data = provider.open(this) as SeekableInputMediaData
        val first = data.createInput(EmptyCoroutineContext)
        val second = data.createInput(EmptyCoroutineContext)
        try {
            assertEquals(4L, data.fileLength())
            first.seekTo(2)
            val bytes = ByteArray(2)
            assertEquals(2, first.read(bytes))
            assertContentEquals(byteArrayOf(3, 4), bytes)
            assertEquals(0L, second.position)
            first.close()
            assertEquals(2, second.read(bytes))
        } finally {
            data.close()
        }
        assertFailsWith<IllegalStateException> { data.createInput(EmptyCoroutineContext) }
        val missing = DocumentsContract.buildDocumentUri(AUTHORITY, "missing")
        assertFailsWith<FileNotFoundException> { access.stat(missing.toString()) }
    }
}

private const val AUTHORITY = "me.him188.ani.localresource.test"

/** Read-only fixture exposing an actual file descriptor through the document protocol. */
class LocalResourceTestDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    private fun result(id: String): Cursor = MatrixCursor(columns).apply {
        when (id) {
            "root" -> addRow(arrayOf<Any?>("root", "Library", Document.MIME_TYPE_DIR, null, 100L))
            "video" -> addRow(arrayOf<Any?>("video", "剧集 01 %.mkv", "video/x-matroska", 4L, 100L))
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(emptyArray<String>())
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = result(documentId)
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == "root" && documentId == "video"
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        if (parentDocumentId == "root") result("video") else throw FileNotFoundException()

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId != "video" || mode != "r") throw FileNotFoundException()
        val file = File(requireNotNull(context).cacheDir, "local-resource-fixture.bin")
        synchronized(this) {
            if (!file.exists()) file.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
