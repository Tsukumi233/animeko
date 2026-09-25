/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.cache.engine.TorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.UnsafeTorrentEngineAccessApi
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.domain.torrent.TorrentEngineType
import me.him188.ani.app.torrent.api.TorrentDownloader
import me.him188.ani.app.torrent.api.TorrentLibInfo
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.peer.PeerInfo
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.source.TorrentMediaSourceReferences
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.platform.annotations.SerializationOnly
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(SerializationOnly::class)
class TorrentResourceBrowserTest {
    private val media = DefaultMedia("rss.release", "rss", "https://site/release",
        ResourceLocation.MagnetLink("magnet:?xt=urn:btih:0123456789012345678901234567890123456789"),
        "season", 0, MediaProperties(subtitleLanguageIds = emptyList(), resolution = "", alliance = ""))
    private val reference = TorrentMediaSourceReferences.entry(media).reference

    private class Access : TorrentEngineAccess {
        val requests = mutableSetOf<Any>()
        override val isServiceConnected = MutableStateFlow(false)
        @OptIn(UnsafeTorrentEngineAccessApi::class)
        override fun requestService(token: Any, use: Boolean): Boolean {
            if (use) requests.add(token) else requests.remove(token)
            isServiceConnected.value = requests.isNotEmpty()
            return isServiceConnected.value
        }
    }

    private class Session : TorrentSession {
        var files: suspend () -> List<TorrentFileEntry> = { emptyList() }
        var activeDownload = false
        var cleanupCalls = 0
        var closed = false
        override val sessionStats = emptyFlow<TorrentSession.Stats?>()
        override suspend fun getName() = "season"
        override suspend fun getFiles() = files()
        override fun getPeers() = emptyList<PeerInfo>()
        override fun getState() = null
        override suspend fun close(): Unit = error("Must not force-close a shared task")
        override suspend fun closeIfNotInUse() {
            currentCoroutineContext().ensureActive()
            cleanupCalls++
            if (!activeDownload) closed = true
        }
    }

    private class Engine(val session: Session) : TorrentEngine {
        override val type = TorrentEngineType.Anitorrent
        override val location = MediaSourceLocation.Local
        override val isSupported = true
        override suspend fun testConnection() = true
        override fun close(): Unit = error("Shared engine must stay open")
        override suspend fun getDownloader() = object : TorrentDownloader {
            override val totalStats = emptyFlow<TorrentDownloader.Stats>()
            override val vendor = TorrentLibInfo("test", "1", true)
            override suspend fun fetchTorrent(uri: String, timeoutSeconds: Int) = EncodedTorrentInfo.createRaw(byteArrayOf(1))
            override suspend fun startDownload(data: EncodedTorrentInfo, parentCoroutineContext: CoroutineContext) = session
            override fun getSaveDirForTorrent(data: EncodedTorrentInfo): SystemPath = error("No payload access")
            override fun listSaves(): List<SystemPath> = error("No payload access")
            override fun close(): Unit = error("Shared downloader must stay open")
        }
    }

    private fun file(path: String, size: Long = 100) = object : TorrentFileEntry {
        override val pathInTorrent = path
        override val fileName = path.substringAfterLast('/')
        override val length = size
        override val fileStats = emptyFlow<TorrentFileEntry.Stats>()
        override val pieces get() = error("No piece access")
        override val supportsStreaming = true
        override fun createHandle(): Nothing = error("Must not request payload download")
        override suspend fun resolveFile(): Nothing = error("No payload access")
        override fun resolveFileMaybeEmptyOrNull(): Nothing = error("No payload access")
        override suspend fun createInput(awaitCoroutineContext: CoroutineContext): Nothing = error("No payload access")
    }

    @Test
    fun `full paths distinguish duplicate names and preserve release identity without downloading`() = runTest {
        val access = Access()
        val session = Session().apply { files = { listOf(file("TV/01.mkv"), file("SP/01.mkv"), file("字幕/01.ass")) } }
        val listing = TorrentResourceBrowser(Engine(session), access).browse(reference)
        assertEquals(media, listing.originalMedia)
        assertEquals(reference, listing.releaseReference)
        assertEquals(listOf("TV/01.mkv", "SP/01.mkv", "字幕/01.ass"), listing.files.map { it.pathInTorrent })
        assertEquals(listOf(true, true, false), listing.files.map { it.isVideo })
        assertTrue(session.closed)
        assertEquals(1, session.cleanupCalls)
        assertTrue(access.requests.isEmpty())
    }

    @Test
    fun `enumerating active task leaves it open`() = runTest {
        val access = Access()
        val playbackToken = Any()
        access.requests.add(playbackToken)
        val session = Session().apply { activeDownload = true; files = { listOf(file("1.mkv")) } }
        TorrentResourceBrowser(Engine(session), access).browse(reference)
        assertFalse(session.closed)
        assertEquals(setOf(playbackToken), access.requests)
    }

    @Test
    fun `missing metadata timeout and cancellation release service and unused session`() = runTest {
        val access = Access()
        val timeout = Session().apply { files = { awaitCancellation() } }
        assertFailsWith<TimeoutCancellationException> {
            TorrentResourceBrowser(Engine(timeout), access, timeoutMillis = 10).browse(reference)
        }
        assertTrue(timeout.closed)
        assertTrue(access.requests.isEmpty())
        val entered = CompletableDeferred<Unit>()
        val cancelled = Session().apply { files = { entered.complete(Unit); awaitCancellation() } }
        val job = launch { TorrentResourceBrowser(Engine(cancelled), access).browse(reference) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cancelled.closed)
        assertEquals(1, cancelled.cleanupCalls)
        assertTrue(access.requests.isEmpty())
    }

    @Test
    fun `cancelling enumeration preserves active playback and its service request`() = runTest {
        val access = Access()
        val playbackToken = Any()
        access.requests.add(playbackToken)
        val entered = CompletableDeferred<Unit>()
        val session = Session().apply {
            activeDownload = true
            files = { entered.complete(Unit); awaitCancellation() }
        }
        val job = launch { TorrentResourceBrowser(Engine(session), access).browse(reference) }
        entered.await()
        job.cancelAndJoin()
        assertFalse(session.closed)
        assertEquals(1, session.cleanupCalls)
        assertEquals(setOf(playbackToken), access.requests)
    }

    @Test
    fun `duplicate complete paths and incomplete metadata are rejected`() = runTest {
        for (files in listOf(listOf(file("a.mkv"), file("a.mkv")), listOf(file("", -1)))) {
            val access = Access()
            val session = Session().apply { this.files = { files } }
            assertFailsWith<IllegalArgumentException> { TorrentResourceBrowser(Engine(session), access).browse(reference) }
            assertTrue(session.closed)
            assertTrue(access.requests.isEmpty())
        }
    }
}
