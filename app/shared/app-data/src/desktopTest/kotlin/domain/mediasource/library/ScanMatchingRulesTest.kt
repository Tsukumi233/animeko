/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.repository.media.ResourceAssociationInput
import me.him188.ani.app.data.repository.media.ResourceIgnoredInput
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepositoryInvalidateTest
import me.him188.ani.app.domain.mediasource.local.ResourceLibraryScanner
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.app.domain.mediasource.torrent.TorrentResourceFile
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.source.MediaSourcePage
import me.him188.ani.datasources.api.source.MediaSourceResourceFactory
import me.him188.ani.datasources.api.source.TorrentMediaSourceReferences
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanMatchingRulesTest {
    private val helper = SubjectCollectionRepositoryInvalidateTest()
    private val parent = MediaResourceRef("source", "folder", "account/folder")
    private fun video(id: String = "first", sort: String = "01") = MediaSourceEntry(
        MediaResourceRef("source", id), "[Group] Show - $sort [1080p].mkv", MediaSourceEntryKind.VIDEO,
    )
    private fun media(reference: MediaResourceRef) = DefaultMedia(
        mediaId = reference.resourceId, mediaSourceId = "source", originalUrl = "",
        download = ResourceLocation.SourceResource(reference), originalTitle = reference.resourceId, publishedTime = 0,
        properties = MediaProperties(null, null, emptyList(), "", "", FileSize.Unspecified, null),
        episodeRange = null, location = MediaSourceLocation.Local, kind = MediaSourceKind.LocalFile,
    )
    private fun rules(reference: MediaResourceRef = parent, target: Int = 11) = ConfirmedResourceMatchingRules(rules = listOf(
        ConfirmedResourceMatchingRule.forParent("rule", reference, listOf(
            ConfirmedResourceEpisodeMapping(EpisodeSort(1), ResourceEpisodeTarget(1, target)),
        )),
    ))
    private fun browser(block: suspend (MediaResourceRef?, String?) -> MediaSourcePage) = object : MediaSourceBrowser {
        override suspend fun browse(parent: MediaResourceRef?, pageToken: String?) = block(parent, pageToken)
    }
    private inner class Fixture(val base: SubjectCollectionRepositoryInvalidateTest.Fixture) {
        val library = ResourceLibraryRepository(base.database.resourceLibraryDao())
        val dao get() = library.dao
        var prepare: suspend (MediaResourceRef) -> Media = { media(it) }
        var files: suspend (MediaResourceRef) -> List<TorrentResourceFile> = { error("Unexpected torrent") }
        val associate = AssociateResourcesUseCase(base.repository, library) { mapOf("source" to object : MediaSourceResourceFactory {
            override suspend fun createMedia(reference: MediaResourceRef, request: MediaFetchRequest) = prepare(reference)
        }) }
        val confirm = ConfirmScanMatchingRulesUseCase(library, base.repository)
        val scanner = ResourceLibraryScanner(library, ApplyScanMatchingRulesUseCase(library, base.repository, associate)) { files(it) }
        suspend fun root() = dao.findScanRoot("root")!!
        suspend fun scan(vararg entries: MediaSourceEntry) = scanner.scan(root(), browser { _, _ -> MediaSourcePage(entries.toList()) })
        suspend fun suggestions() = dao.suggestions().first().flatMap {
            Json.decodeFromString<StoredScanMatchSuggestions>(it.suggestionJson).rows
        }
    }
    private fun test(block: suspend Fixture.() -> Unit) = helper.runRepositoryTest {
        dao.upsert(helper.subject(1, 0))
        database.episodeCollection().upsert(listOf(helper.episode(1, 11, 1, 0), helper.episode(1, 12, 2, 0)))
        service.failingSubjectIds += 1
        val fixture = Fixture(this)
        fixture.dao.upsertScanRoot(LibraryScanRootEntity("root", "source", Json.encodeToString(parent), "Show"))
        fixture.block()
    }

    @Test fun `first complete scan only suggests even when a rule was confirmed before scanning`() = test {
        confirm.confirm("root", rules())
        assertTrue(scan(video()))
        assertTrue(dao.bindings().first().isEmpty())
        assertEquals("SUGGESTED", suggestions().single().status)
        assertTrue(scan(video()))
        assertEquals(11, dao.bindings().first().single().episodeId)
        assertEquals("CONFIRMED", suggestions().single().status)
        assertTrue(scan(video()))
        assertEquals(1, dao.bindings().first().size)
    }

    @Test fun `scan without explicit confirmation never saves a rule or binding`() = test {
        repeat(2) { assertTrue(scan(video())) }
        assertNull(root().matchingRuleJson)
        assertTrue(dao.bindings().first().isEmpty())
        assertTrue(suggestions().single().titleSuggestions.isNotEmpty())
    }

    @Test fun `rule errors stay visible after otherwise complete scan and require repair or removal`() = test {
        dao.upsertScanRoot(root().copy(matchingRuleJson = "{broken", lastCompletedMillis = 1))
        assertTrue(scan(video()))
        assertNotNull(root().error)
        assertEquals("{broken", root().matchingRuleJson)
        assertTrue(dao.bindings().first().isEmpty())
        assertTrue(scan(video()))
        assertNotNull(root().error)
        confirm.remove("root")
        assertNull(root().matchingRuleJson)
        assertNull(root().error)
    }

    @Test fun `confirm rejects foreign scope or unknown episode without invalidating active scan`() = test {
        dao.beginScan(root(), "active")
        assertFailsWith<IllegalArgumentException> { confirm.confirm("root", rules(parent.copy(locator = "other-account"))) }
        assertFailsWith<IllegalArgumentException> { confirm.confirm("root", rules(target = 99)) }
        assertEquals("active", root().activeScanToken)
        assertNull(root().matchingRuleJson)
    }

    @Test fun `same source versions and existing manual target stay ambiguous`() = test {
        scan()
        confirm.confirm("root", rules())
        assertTrue(scan(video(), video("second")))
        assertEquals(setOf("AMBIGUOUS"), suggestions().map { it.status }.toSet())
        assertTrue(dao.bindings().first().isEmpty())
        associate(listOf(ResourceEpisodeSelection(video("outside"), 1, 11)))
        assertTrue(scan(video()))
        assertEquals(1, dao.bindings().first().size)
        assertTrue(suggestions().all { it.status == "AMBIGUOUS" })
    }

    @Test fun `recursive children use their actual parent and cannot inherit root rule`() = test {
        scan()
        confirm.confirm("root", rules())
        val child = parent.copy(resourceId = "child", locator = "account/child")
        assertTrue(scanner.scan(root(), browser { directory, _ ->
            if (directory == parent) MediaSourcePage(listOf(MediaSourceEntry(child, "child", MediaSourceEntryKind.DIRECTORY)))
            else MediaSourcePage(listOf(video()))
        }))
        assertTrue(dao.bindings().first().isEmpty())
        assertEquals(child, suggestions().single().parentReference)
    }

    @Test fun `manual correction during asynchronous prepare prevents stale bindings and missing flags`() = test {
        val first = video()
        val vanished = video("vanished", "02")
        scan(first, vanished)
        confirm.confirm("root", rules())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        prepare = { entered.complete(Unit); release.await(); media(it) }
        coroutineScope {
            val scanning = async { scan(first) }
            entered.await()
            library.associateBatch(listOf(ResourceAssociationInput(first, 1, 12, media(first.reference))), replaceFileBindings = true)
            release.complete(Unit)
            assertFalse(scanning.await())
        }
        assertEquals(12, dao.bindings().first().single().episodeId)
        assertTrue(dao.findResource("source", "vanished")!!.available)
    }

    @Test fun `failed manual preparation keeps scan active`() = test {
        dao.beginScan(root(), "active")
        prepare = { error("Source unavailable") }
        assertFailsWith<IllegalStateException> { associate(listOf(ResourceEpisodeSelection(video(), 1, 11))) }
        assertEquals("active", root().activeScanToken)
        assertTrue(dao.bindings().first().isEmpty())
    }

    @Test fun `cancelled pagination preserves old availability and commits no partial suggestions`() = test {
        scan(video("old"))
        val entered = CompletableDeferred<Unit>()
        coroutineScope {
            val scanning = async {
                scanner.scan(root(), browser { _, page ->
                    if (page == null) MediaSourcePage(listOf(video()), "next")
                    else { entered.complete(Unit); awaitCancellation() }
                })
            }
            entered.await()
            scanning.cancelAndJoin()
        }
        assertTrue(dao.findResource("source", "old")!!.available)
        assertEquals(1, dao.suggestions().first().size)
        assertEquals("扫描已取消", root().error)
    }

    @Test fun `torrent file ignore is preserved by path while another file can be auto associated`() = test {
        val release = TorrentMediaSourceReferences.entry(media(MediaResourceRef("source", "release")).copy(
            download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:0000000000000000000000000000000000000000"),
        ))
        dao.upsertScanRoot(root().copy(referenceJson = Json.encodeToString(release.reference)))
        prepare = { TorrentMediaSourceReferences.decode(it) }
        files = { listOf(
            TorrentResourceFile("A/Show - 01.mkv", "Show - 01.mkv", 20, true),
            TorrentResourceFile("B/Show - 01.mkv", "Show - 01.mkv", 30, true),
        ) }
        assertTrue(scan())
        associate(emptyList(), ignored = listOf(ResourceIgnoredInput(release, "A/Show - 01.mkv")))
        confirm.confirm("root", rules(release.reference))
        assertTrue(scan())
        assertEquals("B/Show - 01.mkv", dao.bindings().first().single().selectedFilePath)
        assertEquals("release", library.decodeMedia(dao.bindings().first().single()).mediaId)
        val suggestion = dao.suggestions().first().single()
        assertEquals(setOf("A/Show - 01.mkv"), library.decodeIgnoredFiles(suggestion))
        assertEquals(setOf("IGNORED", "CONFIRMED"), suggestions().map { it.status }.toSet())
    }
    @Test fun `ignore saved during prepare cannot be overwritten by automatic association`() = test {
        scan()
        confirm.confirm("root", rules())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        prepare = { entered.complete(Unit); release.await(); media(it) }
        coroutineScope {
            val scanning = async { scan(video()) }
            entered.await()
            associate(emptyList(), listOf(ResourceIgnoredInput(video())))
            release.complete(Unit)
            assertFalse(scanning.await())
        }
        assertTrue(dao.bindings().first().isEmpty())
        assertEquals(setOf<String?>(null), library.decodeIgnoredFiles(dao.suggestions().first().single()))
    }

    @Test fun `reference changing during prepare rejects stale scan without modifying the replacement`() = test {
        scan()
        confirm.confirm("root", rules())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        prepare = { entered.complete(Unit); release.await(); media(it) }
        val replacement = video().copy(reference = video().reference.copy(locator = "different-account/file"))
        coroutineScope {
            val scanning = async { scan(video()) }
            entered.await()
            library.index(replacement)
            release.complete(Unit)
            assertFalse(scanning.await())
        }
        assertTrue(dao.bindings().first().isEmpty())
        assertEquals(replacement.reference, library.decodeReference(dao.findResource("source", "first")!!))
        assertNotNull(root().error)
        assertNull(root().activeScanToken)
    }

    @Test fun `competing complete scans cannot automatically claim the same source episode`() = test {
        scan()
        confirm.confirm("root", rules())
        val otherParent = parent.copy(resourceId = "other-folder")
        dao.upsertScanRoot(root().copy(id = "other-root", referenceJson = Json.encodeToString(otherParent),
            matchingRuleJson = rules(otherParent).encode()))
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        prepare = {
            if (it.resourceId == "first") { firstEntered.complete(Unit); firstRelease.await() }
            else { secondEntered.complete(Unit); secondRelease.await() }
            media(it)
        }
        coroutineScope {
            val first = async { scan(video()) }
            firstEntered.await()
            val second = async { scanner.scan(dao.findScanRoot("other-root")!!, browser { _, _ -> MediaSourcePage(listOf(video("second"))) }) }
            secondEntered.await()
            firstRelease.complete(Unit)
            assertTrue(first.await())
            secondRelease.complete(Unit)
            assertFalse(second.await())
        }
        assertEquals(1, dao.bindings().first().size)
        assertNotNull(dao.findScanRoot("other-root")!!.error)
    }

    @Test fun `rule saved for a previous account scope remains visible but cannot auto associate`() = test {
        scan()
        confirm.confirm("root", rules())
        dao.upsertScanRoot(root().copy(referenceJson = Json.encodeToString(parent.copy(locator = "new-account/folder"))))
        assertTrue(scan(video()))
        assertTrue(dao.bindings().first().isEmpty())
        assertNotNull(root().error)
    }
    @Test fun `explicit cross season special mapping keeps typed sort and target identity`() = test {
        base.dao.upsert(helper.subject(2, 0))
        base.database.episodeCollection().upsert(helper.episode(2, 21, 1, 0).copy(episodeType = EpisodeType.SP))
        base.service.failingSubjectIds += 2
        scan()
        confirm.confirm("root", ConfirmedResourceMatchingRules(rules = listOf(
            ConfirmedResourceMatchingRule.forParent("special", parent, listOf(
                ConfirmedResourceEpisodeMapping(EpisodeSort("SP01"), ResourceEpisodeTarget(2, 21)),
                ConfirmedResourceEpisodeMapping(EpisodeSort(1), ResourceEpisodeTarget(1, 11)),
            )),
        )))
        assertTrue(scan(video(), video("special").copy(name = "[Group] Show [SP01] [1080p].mkv")))
        assertEquals(setOf(1 to 11, 2 to 21), dao.bindings().first().map { it.subjectId to it.episodeId }.toSet())
    }

    @Test fun `repeated pagination token is an incomplete scan and cannot mark old resource missing`() = test {
        scan(video("old"))
        assertFailsWith<IllegalStateException> {
            scanner.scan(root(), browser { _, _ -> MediaSourcePage(listOf(video()), "same-page") })
        }
        assertTrue(dao.findResource("source", "old")!!.available)
        assertNotNull(root().error)
        assertEquals(1, dao.suggestions().first().size)
    }
    @Test fun `invalid candidate source aborts the scan without committing bindings or missing flags`() = test {
        scan(video("old"))
        confirm.confirm("root", rules())
        prepare = { media(it).copy(mediaSourceId = "different-source") }
        assertFailsWith<IllegalArgumentException> { scan(video()) }
        assertTrue(dao.bindings().first().isEmpty())
        assertTrue(dao.findResource("source", "old")!!.available)
        assertNotNull(root().error)
    }
}
