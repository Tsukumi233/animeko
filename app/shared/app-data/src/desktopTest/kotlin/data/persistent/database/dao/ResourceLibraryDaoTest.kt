package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceLibraryDaoTest {
    private fun test(block: suspend (ResourceLibraryDao) -> Unit) = runBlocking {
        val database = createTestAniDatabase()
        try {
            block(database.resourceLibraryDao())
        } finally {
            database.close()
        }
    }

    private fun resource() = LibraryResourceEntity("video", "disk", "01.mkv", "{}", "01.mkv", "VIDEO")

    @Test
    fun `correcting one video replaces its old episode without removing other versions`() = test { dao ->
        val otherVersion = resource().copy(id = "other", resourceKey = "other.mkv")
        val original = LibraryEpisodeBindingEntity("video", "disk", 1, 11, "{}")
        val other = original.copy(resourceId = "other")
        dao.confirmBindings(listOf(resource(), otherVersion), listOf(original, other))
        val correction = original.copy(subjectId = 2, episodeId = 21)
        dao.confirmBindings(listOf(resource()), listOf(correction), replaceFileBindings = true)
        assertEquals(setOf(correction, other), dao.bindings().first().toSet())
    }

    @Test
    fun `torrent file corrections can swap episodes atomically and retain untouched files`() = test { dao ->
        val torrent = resource().copy(entryKind = "TORRENT")
        val first = LibraryEpisodeBindingEntity("video", "disk", 1, 11, "{}", "A/video.mkv")
        val second = first.copy(episodeId = 12, selectedFilePath = "B/video.mkv")
        val third = first.copy(episodeId = 13, selectedFilePath = "C/video.mkv")
        dao.confirmBindings(listOf(torrent), listOf(first, second, third))
        val swapped = listOf(first.copy(episodeId = 12), second.copy(episodeId = 11))
        dao.confirmBindings(listOf(torrent), swapped, replaceFileBindings = true)
        assertEquals((swapped + third).toSet(), dao.bindings().first().toSet())
    }

    @Test
    fun `batch validates every binding before exposing any associations`() = test { dao ->
        val first = resource()
        val second = first.copy(id = "video2", resourceKey = "02.mkv")
        val bindings = listOf(
            LibraryEpisodeBindingEntity(first.id, "disk", 1, 2, "{}"),
            LibraryEpisodeBindingEntity(second.id, "wrong-source", 3, 4, "{}"),
        )
        assertFailsWith<IllegalArgumentException> { dao.confirmBindings(listOf(first, second), bindings) }
        assertTrue(dao.resources().first().isEmpty())
        assertTrue(dao.bindings().first().isEmpty())
        dao.confirmBindings(listOf(first, second), bindings.map { it.copy(sourceId = "disk") })
        assertEquals(2, dao.bindings().first().size)
    }

    @Test
    fun `confirmed association is idempotent without a collection row`() = test { dao ->
        dao.upsertSuggestion(LibraryMatchSuggestionEntity("video", "{}"))
        val binding = LibraryEpisodeBindingEntity("video", "disk", 1, 2, "{}")
        repeat(2) { dao.confirmBinding(resource(), binding) }
        assertEquals(listOf(binding), dao.bindings().first())
        assertTrue(dao.suggestions().first().isEmpty())
        dao.removeBinding("video", 1, 2)
        assertEquals(resource(), dao.findResource("video"))
    }

    @Test
    fun `stale or unfinished scans preserve availability`() = test { dao ->
        dao.upsertResource(resource())
        dao.upsertScanRoot(LibraryScanRootEntity("root", "disk", "{}", "root", activeScanToken = "new"))
        dao.upsertScanEntry(LibraryScanEntryEntity("root", "video", "old"))
        assertFalse(dao.completeScan("root", "old", 10))
        assertTrue(dao.findResource("video")!!.available)
        assertTrue(dao.completeScan("root", "new", 20))
        assertFalse(dao.findResource("video")!!.available)
        assertEquals(20L, dao.findScanRoot("root")!!.lastCompletedMillis)
    }

    @Test
    fun `overlapping root retains resource until both completed scans miss it`() = test { dao ->
        dao.upsertResource(resource())
        for (root in listOf("parent", "child")) {
            dao.upsertScanRoot(LibraryScanRootEntity(root, "disk", "{}", root, activeScanToken = "new"))
            dao.upsertScanEntry(LibraryScanEntryEntity(root, "video", "old"))
        }
        assertTrue(dao.completeScan("parent", "new", 10))
        assertTrue(dao.findResource("video")!!.available)
        assertTrue(dao.completeScan("child", "new", 20))
        assertFalse(dao.findResource("video")!!.available)
    }
}
