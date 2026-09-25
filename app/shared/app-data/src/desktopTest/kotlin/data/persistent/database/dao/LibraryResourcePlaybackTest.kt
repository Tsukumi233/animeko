package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.mediasource.library.libraryPlaybackTestMedia
import me.him188.ani.app.domain.mediasource.library.resolveForPlayback
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryResourcePlaybackTest {
    @Test
    fun `exact confirmed tuple retains original release identity and full torrent path`() = runBlocking {
        val database = createTestAniDatabase()
        try {
            val repository = ResourceLibraryRepository(database.resourceLibraryDao())
            val media = libraryPlaybackTestMedia(mediaId = "release", mediaSourceId = "torrent",
                download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:release"))
            val entry = MediaSourceEntry(MediaResourceRef("torrent", "release", "opaque"), "release", MediaSourceEntryKind.TORRENT)
            val resource = repository.associate(entry, 1, 11, media, "Season 1/video.mkv")
            repository.associate(entry, 1, 12, media, "Season 2/video.mkv")
            val chosen = requireNotNull(repository.resolveForPlayback(resource.id, 1, 12))
            assertEquals("release", chosen.mediaId)
            assertEquals(media.download, chosen.download)
            assertEquals(mapOf("12" to "Season 2/video.mkv"), chosen.association!!.selectedFilePaths)
            assertEquals(listOf("12"), chosen.association!!.episodeIds)
            assertNull(repository.resolveForPlayback(resource.id, 2, 12))
            assertNull(repository.resolveForPlayback(resource.id, 1, 13))
            repository.removeBinding(resource.id, 1, 12)
            assertNull(repository.resolveForPlayback(resource.id, 1, 12))
        } finally { database.close() }
    }

    @Test
    fun `stored source reference overrides candidate locator after reindex`() = runBlocking {
        val database = createTestAniDatabase()
        try {
            val repository = ResourceLibraryRepository(database.resourceLibraryDao())
            val reference = MediaResourceRef("disk", "file", "old")
            val entry = MediaSourceEntry(reference, "episode.mkv", MediaSourceEntryKind.VIDEO)
            val media = libraryPlaybackTestMedia(mediaSourceId = "disk", download = ResourceLocation.SourceResource(reference))
            val resource = repository.associate(entry, 1, 11, media)
            val updated = reference.copy(locator = "current")
            repository.index(entry.copy(reference = updated))
            assertEquals(ResourceLocation.SourceResource(updated), repository.resolveForPlayback(resource.id, 1, 11)!!.download)
        } finally { database.close() }
    }
}
