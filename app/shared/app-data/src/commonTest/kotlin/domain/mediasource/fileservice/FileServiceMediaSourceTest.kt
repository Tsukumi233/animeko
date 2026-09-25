/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.media.download.capability.MediaDownloadAccessRequest
import me.him188.ani.app.domain.media.download.capability.PreparedDownloadAccess
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.SeekableInputMediaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileServiceMediaSourceTest {
    private val args = FileServiceArguments("NAS", FileServiceProtocol.SMB, "nas", "video")
    private val request = MediaFetchRequest("subject", "episode", subjectNames = listOf("番剧"), episodeSort = EpisodeSort(1), episodeName = "第一集")

    @Test fun `configured root is browsable scoped and stable across display renames`() = runTest {
        var credentials = FileServiceCredentials("user", "secret")
        val provider = FileServiceCredentialProvider { credentials }
        val source = FileServiceMediaSource("source", args, provider, FakeSmbAccess())
        val root = source.rootEntry()
        assertEquals(MediaSourceEntryKind.DIRECTORY, root.kind)
        assertEquals("NAS", root.name)
        assertEquals(source.browse().entries.map { it.reference }, source.browse(root.reference).entries.map { it.reference })
        assertEquals(root.reference, source.browse(root.reference).entries.single().parent)
        assertTrue(!root.reference.locator.contains("secret"))
        val renamed = FileServiceMediaSource("source", args.copy(name = "Renamed"), provider, FakeSmbAccess())
        assertEquals(root.reference, renamed.rootEntry().reference)
        credentials = FileServiceCredentials("another", "secret")
        assertFailsWith<IllegalArgumentException> { source.browse(root.reference) }
    }

    @Test
    fun `account and endpoint changes reject saved references without exposing credentials`() = runTest {
        var credentials = FileServiceCredentials("first-user", "secret")
        val access = FakeSmbAccess()
        val provider = FileServiceCredentialProvider { credentials }
        val source = FileServiceMediaSource("source", args, provider, access)
        val reference = source.browse().entries.single().reference
        assertTrue(!reference.locator.contains("secret") && !reference.locator.contains("first-user"))
        assertTrue(!Json.encodeToString(args).contains("secret"))
        credentials = FileServiceCredentials("second-user", "other")
        assertFailsWith<IllegalArgumentException> { source.resolveResource(reference, metadata()) }
        credentials = FileServiceCredentials("first-user", "secret")
        val changed = FileServiceMediaSource("source", args.copy(endpoint = "other-nas"), provider, access)
        assertFailsWith<IllegalArgumentException> { changed.resolveResource(reference, metadata()) }
    }

    @Test
    fun `download checks server version before reopening and closes changed handle`() = runTest {
        val access = FakeSmbAccess()
        val source = FileServiceMediaSource("source", args, FileServiceCredentialProvider { FileServiceCredentials("user", "secret") }, access)
        val ref = source.browse().entries.single().reference
        val media = source.createMedia(ref, request)
        val prepared = source.prepare(MediaDownloadAccessRequest(media, metadata()), backgroundScope) as PreparedDownloadAccess.ByteRange
        access.version = "new-version"
        assertFailsWith<FileServiceAccessException> { prepared.open() }
        assertEquals(1, access.closes)
    }

    @Test
    fun `byte access supports offsets beyond four GiB and media close releases readers`() = runTest {
        val access = FakeSmbAccess()
        val source = FileServiceMediaSource("source", args, FileServiceCredentialProvider { null }, access)
        val ref = source.browse().entries.single().reference
        val data = source.resolveResource(ref, metadata()).open(backgroundScope)
        val input = (data as SeekableInputMediaData).createInput(coroutineContext)
        input.seekTo(4_294_967_300L)
        assertEquals(4, input.read(ByteArray(4)))
        assertEquals(4_294_967_304L, input.position)
        data.close()
        assertEquals(1, access.closes)
        assertFailsWith<IllegalArgumentException> { source.browse(pageToken = "fake-page") }
    }

    private fun metadata() = EpisodeMetadata("第一集", EpisodeSort(1), EpisodeSort(1))

    private class FakeSmbAccess : SmbFileServiceAccess {
        var version = "version-one"
        var closes = 0
        private val length = 5_368_709_120L
        override suspend fun list(path: String, credentials: FileServiceCredentials) = listOf(entry())
        override suspend fun stat(path: String, credentials: FileServiceCredentials) = entry()
        private fun entry() = FileServiceEntry("中文 01.mkv", "中文 01.mkv", false, length, 1, version)
        override suspend fun open(path: String, credentials: FileServiceCredentials): FileServiceOpenFile = object : FileServiceOpenFile {
            override val size = length
            override val contentIdentity = version
            private var closed = false
            override suspend fun readAt(offset: Long, buffer: ByteArray, length: Int): Int = length
            override fun close() { if (!closed) { closed = true; closes++ } }
            override fun asSeekableInput(): SeekableInput {
                val owner = this
                return object : SeekableInput {
                override var position = 0L
                override val size = length
                override val bytesRemaining get() = size - position
                override fun seekTo(position: Long) { this.position = position }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int { position += length; return length }
                override fun close() = owner.close()
                }
            }
        }
        override fun close() = Unit
    }
}
