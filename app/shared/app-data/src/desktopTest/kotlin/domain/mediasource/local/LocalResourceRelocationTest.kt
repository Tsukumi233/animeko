package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.LibraryMatchSuggestionEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryScanEntryEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.repository.media.LibraryIgnoredFiles
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceEpisodeMapping
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceMatchingRule
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceMatchingRules
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeTarget
import me.him188.ani.app.domain.mediasource.library.libraryPlaybackTestMedia
import me.him188.ani.app.domain.mediasource.library.resolveForPlayback
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalResourceRelocationTest {
    private class Access : LocalResourceAccess {
        val entries = linkedMapOf<String, LocalResourceEntry>()
        var opaque = false
        var duplicateChildren = false
        fun file(uri: String, size: Long = 10, modified: Long = 100) {
            entries[uri] = LocalResourceEntry(uri, uri.substringAfterLast('/'), false, size, modified)
        }
        fun directory(uri: String) {
            entries[uri] = LocalResourceEntry(uri, uri.substringAfterLast('/'), true, null, 100)
        }
        override suspend fun stat(uri: String) = entries[uri] ?: error("File missing: $uri")
        override suspend fun list(directoryUri: String): List<LocalResourceEntry> =
            entries.values.filter { it.uri.substringBeforeLast('/') == directoryUri }.let { if (duplicateChildren) it + it else it }
        override fun relativePathSegments(rootUri: String, resourceUri: String): List<String>? = when {
            opaque -> null
            rootUri == resourceUri -> emptyList()
            resourceUri.startsWith("$rootUri/") -> resourceUri.removePrefix("$rootUri/").split('/')
            else -> null
        }
        override suspend fun persistReadPermission(uri: String) = Unit
        override suspend fun releaseReadPermission(uri: String) = Unit
        override suspend fun createMediaDataProvider(uri: String): MediaDataProvider<*> = error("Not used")
    }

    private class Fixture {
        val database = createTestAniDatabase()
        val library = ResourceLibraryRepository(database.resourceLibraryDao())
        val access = Access()
        val source = LocalFileMediaSource("disk", access, { emptyList() })
        val useCase = LocalResourceRelocationUseCase(library, access)
        suspend fun associated(uri: String, episode: Int = 11) = source.entry(uri).let { entry ->
            library.associate(entry, 1, episode, libraryPlaybackTestMedia(mediaId = "stable-$episode", mediaSourceId = "disk",
                download = ResourceLocation.SourceResource(entry.reference)))
        }
        suspend fun root(uri: String = "/old", id: String = "root"): LibraryScanRootEntity {
            access.directory(uri)
            return LibraryScanRootEntity(id, "disk", Json.encodeToString(MediaResourceRef("disk", uri)), uri,
                lastCompletedMillis = 10, activeScanToken = "scan").also { library.dao.upsertScanRoot(it) }
        }
    }
    private fun test(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.database.close() }
    }

    @Test fun `explicit replacement retains record bindings ignored decisions and media identity across restart`() = test {
        access.file("/old/video.mp4")
        access.file("/new/different.mp4", 20)
        val resource = associated("/old/video.mp4")
        val ignored = LibraryMatchSuggestionEntity(resource.id, Json.encodeToString(LibraryIgnoredFiles(setOf(null))), true)
        library.dao.upsertSuggestion(ignored)
        library.dao.upsertResource(resource.copy(available = false))
        val bindings = library.bindings.first()
        val revision = library.revision.value
        val plan = useCase.prepareFile(resource.id, "/new/different.mp4", source)
        assertEquals(10L, plan.items.single().oldSize)
        assertEquals(20L, plan.items.single().newSize)
        assertEquals("/old/video.mp4", library.decodeReference(library.dao.findResource(resource.id)!!).locator)
        useCase.confirm(plan)
        val reopened = ResourceLibraryRepository(library.dao)
        val current = reopened.resources.first().single()
        assertEquals(resource.id, current.id)
        assertEquals("/new/different.mp4", current.resourceKey)
        assertTrue(current.available)
        val refreshed = reopened.bindings.first().single()
        assertEquals(bindings.single(), refreshed.copy(mediaJson = bindings.single().mediaJson))
        assertEquals("different.mp4", reopened.decodeMedia(refreshed).originalTitle)
        assertEquals(20L, reopened.decodeMedia(refreshed).properties.size.inBytes)
        assertEquals(ignored, reopened.dao.findSuggestion(resource.id))
        assertEquals(revision + 1, library.revision.value)
        val media = reopened.resolveForPlayback(resource.id, 1, 11)!!
        assertEquals("stable-11", media.mediaId)
        assertEquals(ResourceLocation.SourceResource(MediaResourceRef("disk", "/new/different.mp4")), media.download)
    }

    @Test fun `same granted file can repair availability without changing identity`() = test {
        access.file("/old/video.mp4")
        val resource = associated("/old/video.mp4")
        library.dao.upsertResource(resource.copy(available = false))
        val root = root("/old/video.mp4", "picked-file")
        access.file("/old/video.mp4")
        useCase.confirm(useCase.prepareFile(resource.id, "/old/video.mp4", source))
        assertTrue(library.dao.findResource(resource.id)!!.available)
        assertEquals(root.id, library.dao.scanRoots().first().single().id)
    }

    @Test fun `file moved outside directory is detached from its old scan membership`() = test {
        val root = root()
        access.file("/old/01.mp4")
        access.file("/new/01.mp4")
        val resource = associated("/old/01.mp4")
        library.dao.upsertScanEntry(LibraryScanEntryEntity(root.id, resource.id, "old-scan"))
        useCase.confirm(useCase.prepareFile(resource.id, "/new/01.mp4", source))
        assertTrue(library.dao.relocationSnapshot("disk").scanEntries.isEmpty())
        val current = library.dao.findScanRoot(root.id)!!
        library.dao.beginScan(current, "fresh-scan")
        library.dao.completeScan(root.id, "fresh-scan", 50)
        assertTrue(library.dao.findResource(resource.id)!!.available)
    }

    @Test fun `indexed destination is rejected rather than merged`() = test {
        access.file("/old/video.mp4")
        access.file("/new/video.mp4")
        val old = associated("/old/video.mp4")
        associated("/new/video.mp4", 12)
        val snapshot = library.dao.relocationSnapshot("disk")
        val failure = assertFailsWith<LocalRelocationException> { useCase.prepareFile(old.id, "/new/video.mp4", source) }
        assertEquals(LocalRelocationException.Reason.DESTINATION_CONFLICT, failure.reason)
        assertEquals(snapshot, library.dao.relocationSnapshot("disk"))
    }

    @Test fun `manual correction after preview wins over relocation`() = test {
        access.file("/old/video.mp4")
        access.file("/new/video.mp4")
        val old = associated("/old/video.mp4")
        val plan = useCase.prepareFile(old.id, "/new/video.mp4", source)
        library.removeBinding(old.id, 1, 11)
        val snapshot = library.dao.relocationSnapshot("disk")
        val revision = library.revision.value
        assertFailsWith<LocalRelocationException> { useCase.confirm(plan) }
        assertEquals(snapshot, library.dao.relocationSnapshot("disk"))
        assertEquals(revision, library.revision.value)
    }

    @Test fun `destination indexed after preview rejects transaction before any location changes`() = test {
        access.file("/old/video.mp4")
        access.file("/new/video.mp4")
        val old = associated("/old/video.mp4")
        val plan = useCase.prepareFile(old.id, "/new/video.mp4", source)
        library.index(source.entry("/new/video.mp4"))
        val snapshot = library.dao.relocationSnapshot("disk")
        assertFailsWith<LocalRelocationException> { useCase.confirm(plan) }
        assertEquals(snapshot, library.dao.relocationSnapshot("disk"))
    }

    @Test fun `target changed after preview is rejected`() = test {
        access.file("/old/video.mp4")
        access.file("/new/video.mp4")
        val old = associated("/old/video.mp4")
        val plan = useCase.prepareFile(old.id, "/new/video.mp4", source)
        access.file("/new/video.mp4", 99)
        assertFailsWith<LocalRelocationException> { useCase.confirm(plan) }
        assertEquals(old, library.dao.findResource(old.id))
    }

    @Test fun `directory relocation preserves exact nested paths and matching rules while invalidating old scan`() = test {
        val root = root()
        val rule = ConfirmedResourceMatchingRule.forParent("rule", MediaResourceRef("disk", "/old"),
            listOf(ConfirmedResourceEpisodeMapping(EpisodeSort(1), ResourceEpisodeTarget(1, 11))))
        library.dao.upsertScanRoot(root.copy(matchingRuleJson = ConfirmedResourceMatchingRules(rules = listOf(rule)).encode()))
        access.file("/old/A/video.mp4")
        access.file("/old/B/video.mp4", 20)
        val first = associated("/old/A/video.mp4")
        val second = library.index(source.entry("/old/B/video.mp4"))
        val ignored = LibraryMatchSuggestionEntity(second.id, Json.encodeToString(LibraryIgnoredFiles(setOf(null))), true)
        library.dao.upsertSuggestion(ignored)
        for (resource in listOf(first, second)) library.dao.upsertScanEntry(LibraryScanEntryEntity(root.id, resource.id, "scan"))
        // Only the selected replacement directory is accessed; the previous disk is unavailable.
        access.entries.clear()
        access.directory("/new")
        access.directory("/new/A")
        access.directory("/new/B")
        access.file("/new/A/video.mp4")
        access.file("/new/B/video.mp4", 20)
        library.dao.upsertScanRoot(library.dao.findScanRoot(root.id)!!.copy(activeScanToken = "scan"))
        val bindings = library.bindings.first()
        val plan = useCase.prepareDirectory(root.id, "/new", source)
        assertEquals(setOf("A/video.mp4", "B/video.mp4"), plan.items.map { it.relativePath }.toSet())
        useCase.confirm(plan)
        assertEquals(bindings, library.bindings.first())
        assertEquals(ignored, library.dao.findSuggestion(second.id))
        assertEquals("/new/A/video.mp4", library.decodeReference(library.dao.findResource(first.id)!!).locator)
        assertEquals("/new/B/video.mp4", library.decodeReference(library.dao.findResource(second.id)!!).locator)
        val movedRoot = library.dao.findScanRoot(root.id)!!
        assertEquals(MediaResourceRef("disk", "/new"), Json.decodeFromString(movedRoot.referenceJson))
        assertEquals("/new", ConfirmedResourceMatchingRules.decode(movedRoot.matchingRuleJson!!).rules.single().parentLocator)
        assertEquals(null, movedRoot.lastCompletedMillis)
        assertFalse(library.dao.recordScanEntry(root.id, "scan", first))
        assertTrue(library.dao.relocationSnapshot("disk").scanEntries.all { it.present })
    }

    @Test fun `directory requires every indexed file with matching metadata and unambiguous relative path`() = test {
        val root = root()
        access.file("/old/01.mp4")
        val old = associated("/old/01.mp4")
        library.dao.upsertScanEntry(LibraryScanEntryEntity(root.id, old.id, "scan"))
        access.directory("/new")
        val snapshot = library.dao.relocationSnapshot("disk")
        for (scenario in listOf("missing", "size", "mtime", "duplicate")) {
            access.entries.remove("/new/01.mp4")
            access.duplicateChildren = false
            when (scenario) {
                "size" -> access.file("/new/01.mp4", 99)
                "mtime" -> access.file("/new/01.mp4", modified = 200)
                "duplicate" -> { access.file("/new/01.mp4"); access.duplicateChildren = true }
            }
            assertFailsWith<LocalRelocationException>(scenario) { useCase.prepareDirectory(root.id, "/new", source) }
            assertEquals(snapshot, library.dao.relocationSnapshot("disk"))
        }
    }

    @Test fun `overlapping roots are rejected without moving a subset`() = test {
        val root = root()
        this.root("/old/child", "child")
        access.directory("/new")
        val failure = assertFailsWith<LocalRelocationException> { useCase.prepareDirectory(root.id, "/new", source) }
        assertEquals(LocalRelocationException.Reason.OVERLAPPING_ROOTS, failure.reason)
    }

    @Test fun `opaque provider supports explicit file replacement but never guesses a directory mapping`() = test {
        val root = root()
        access.file("/old/01.mp4")
        access.file("/new/01.mp4")
        val old = associated("/old/01.mp4")
        access.opaque = true
        val failure = assertFailsWith<LocalRelocationException> { useCase.prepareDirectory(root.id, "/new", source) }
        assertEquals(LocalRelocationException.Reason.UNSUPPORTED_PATH, failure.reason)
        useCase.confirm(useCase.prepareFile(old.id, "/new/01.mp4", source))
        assertEquals("/new/01.mp4", library.dao.findResource(old.id)!!.resourceKey)
    }
}
