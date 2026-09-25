package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.repository.media.ResourceAssociationInput
import me.him188.ani.app.data.repository.media.ResourceIgnoredInput
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.mediasource.library.StoredResourceMatchSuggestion
import me.him188.ani.app.domain.mediasource.library.StoredScanMatchSuggestions
import me.him188.ani.app.domain.mediasource.library.libraryPlaybackTestMedia
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceTorrentCatalogTest {
    private fun test(block: suspend (ResourceLibraryRepository) -> Unit) = runBlocking<Unit> {
        val db = createTestAniDatabase()
        try { block(ResourceLibraryRepository(db.resourceLibraryDao())) } finally { db.close() }
    }
    private val release = MediaSourceEntry(MediaResourceRef("bt", "release", "stable"), "Collection", MediaSourceEntryKind.TORRENT)
    private val media = libraryPlaybackTestMedia(mediaSourceId = "bt")

    @Test fun `metadata preserves decisions and subsequent association preserves full catalogue`() = test { library ->
        val resource = library.associateBatch(emptyList(), ignored = listOf(ResourceIgnoredInput(release, "B/01.mkv"))).single()
        library.associate(release, 1, 11, media, "A/01.mkv")
        val before = library.bindings.first()
        library.recordTorrentFiles(resource.id, release.reference, listOf("A/01.mkv", "B/01.mkv", "C/01.mkv"))
        assertEquals(before, library.bindings.first())
        library.associateBatch(listOf(ResourceAssociationInput(release, 1, 12, media, "C/01.mkv")), replaceFileBindings = true)
        val saved = library.dao.findSuggestion(resource.id)!!
        val payload = Json.decodeFromString<StoredScanMatchSuggestions>(saved.suggestionJson)
        assertTrue(payload.catalogComplete)
        assertEquals(listOf("A/01.mkv", "B/01.mkv", "C/01.mkv"), payload.rows.map { it.selectedFilePath })
        assertEquals(setOf("B/01.mkv"), library.decodeIgnoredFiles(saved))
        assertEquals(setOf("A/01.mkv", "C/01.mkv"), library.bindings.first().map { it.selectedFilePath }.toSet())
        assertTrue(library.bindings.first().all { library.decodeMedia(it).mediaId == media.mediaId })
    }

    @Test fun `metadata retains matching suggestions for the same full path`() = test { library ->
        val resource = library.index(release)
        val row = StoredResourceMatchSuggestion("A/01.mkv", listOf("Recognized"), null, "AMBIGUOUS", emptyList(), null)
        library.dao.upsertSuggestion(LibraryMatchSuggestionEntity(resource.id, Json.encodeToString(StoredScanMatchSuggestions(rows = listOf(row)))))
        library.recordTorrentFiles(resource.id, release.reference, listOf("A/01.mkv", "B/01.mkv"))
        val payload = Json.decodeFromString<StoredScanMatchSuggestions>(library.dao.findSuggestion(resource.id)!!.suggestionJson)
        assertEquals(row, payload.rows.first())
        assertEquals(2, payload.rows.size)
    }

    @Test fun `late metadata from old reference cannot overwrite relocated resource decisions`() = test { library ->
        val resource = library.index(release)
        library.index(release.copy(reference = release.reference.copy(locator = "new")))
        assertFailsWith<IllegalArgumentException> { library.recordTorrentFiles(resource.id, release.reference, listOf("01.mkv")) }
        assertEquals(null, library.dao.findSuggestion(resource.id))
        assertTrue(library.bindings.first().isEmpty())
    }

    @Test fun `unsupported catalogue version rejects metadata without erasing it`() = test { library ->
        val resource = library.index(release)
        val saved = LibraryMatchSuggestionEntity(resource.id, """{"version":2}""")
        library.dao.upsertSuggestion(saved)
        assertFailsWith<IllegalArgumentException> { library.recordTorrentFiles(resource.id, release.reference, listOf("01.mkv")) }
        assertEquals(saved, library.dao.findSuggestion(resource.id))
    }
}
