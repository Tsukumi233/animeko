package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.repository.media.ResourceAssociationInput
import me.him188.ani.app.data.repository.media.ResourceIgnoredInput
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.mediasource.library.libraryPlaybackTestMedia
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryIgnoredFilesTest {
    private fun test(block: suspend (ResourceLibraryRepository) -> Unit) = runBlocking {
        val database = createTestAniDatabase()
        try { block(ResourceLibraryRepository(database.resourceLibraryDao())) }
        finally { database.close() }
    }

    private val video = MediaSourceEntry(MediaResourceRef("disk", "video"), "01.mkv", MediaSourceEntryKind.VIDEO)
    private val torrent = video.copy(kind = MediaSourceEntryKind.TORRENT)
    private val media = libraryPlaybackTestMedia(mediaSourceId = "disk")

    @Test fun `ignored video survives repository recreation and reindexing`() = test { repository ->
        val resource = repository.associateBatch(emptyList(), ignored = listOf(ResourceIgnoredInput(video))).single()
        repository.index(video.copy(size = 1024))
        val reopened = ResourceLibraryRepository(repository.dao)
        assertEquals(setOf<String?>(null), reopened.decodeIgnoredFiles(repository.dao.findSuggestion(resource.id)!!))
        assertTrue(reopened.bindings.first().isEmpty())
        assertEquals(1, reopened.resources.first().size)
    }

    @Test fun `confirming a torrent file retains ignored same-name files in other folders`() = test { repository ->
        val resource = repository.associateBatch(emptyList(), ignored = listOf(
            ResourceIgnoredInput(torrent, "B/video.mkv"), ResourceIgnoredInput(torrent, "C/video.mkv"),
        )).single()
        repository.associate(torrent, 1, 11, media, "A/video.mkv")
        assertEquals(setOf("B/video.mkv", "C/video.mkv"), repository.decodeIgnoredFiles(repository.dao.findSuggestion(resource.id)!!))
        repository.associateBatch(listOf(ResourceAssociationInput(torrent, 1, 12, media, "B/video.mkv")), replaceFileBindings = true)
        assertEquals(setOf("C/video.mkv"), repository.decodeIgnoredFiles(repository.dao.findSuggestion(resource.id)!!))
        assertEquals(setOf("A/video.mkv", "B/video.mkv"), repository.bindings.first().map { it.selectedFilePath }.toSet())
    }

    @Test fun `same file cannot be confirmed and ignored in one batch`() = test { repository ->
        assertFailsWith<IllegalArgumentException> {
            repository.associateBatch(listOf(ResourceAssociationInput(video, 1, 11, media)), ignored = listOf(ResourceIgnoredInput(video)))
        }
        assertTrue(repository.resources.first().isEmpty())
        assertTrue(repository.bindings.first().isEmpty())
        assertTrue(repository.dao.suggestions().first().isEmpty())
    }

    @Test fun `invalid suggestion rejects whole confirmation before writing resources`() = test { repository ->
        val resource = LibraryResourceEntity("video", "disk", "video", "{}", "01.mkv", "VIDEO")
        assertFailsWith<IllegalArgumentException> {
            repository.dao.confirmBindings(listOf(resource), listOf(LibraryEpisodeBindingEntity("video", "disk", 1, 11, "{}")),
                suggestions = listOf(LibraryMatchSuggestionEntity("foreign", "{}", true)))
        }
        assertTrue(repository.resources.first().isEmpty())
        assertTrue(repository.bindings.first().isEmpty())
    }
}
