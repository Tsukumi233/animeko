package me.him188.ani.app.ui.resource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.domain.media.cache.engine.AlwaysUseTorrentEngineAccess
import me.him188.ani.app.domain.mediasource.torrent.TorrentResourceBrowser
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourcePage
import me.him188.ani.datasources.api.source.MediaSourceSearchScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResourceBrowserControllerTest {
    private fun torrent() = TorrentResourceBrowser({ null }, AlwaysUseTorrentEngineAccess)
    private fun entry(id: String, source: String = "test") = MediaSourceEntry(MediaResourceRef(source, id), id, MediaSourceEntryKind.VIDEO)

    @Test fun `real root receives listing and back leaves source while nested folders remain navigable`() = runTest {
        val root = MediaSourceEntry(MediaResourceRef("test", "root", "scoped-root"), "NAS", MediaSourceEntryKind.DIRECTORY)
        val child = root.copy(reference = MediaResourceRef("test", "child"), name = "Season")
        val grandchild = root.copy(reference = MediaResourceRef("test", "grandchild"), name = "Extras")
        val requested = mutableListOf<MediaResourceRef?>()
        val source = object : MediaSourceBrowser {
            override suspend fun rootEntry() = root
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
                requested += parent
                return MediaSourcePage(listOf(child))
            }
        }
        val controller = ResourceBrowserController(backgroundScope, torrent())
        controller.open("test", "NAS", source)
        runCurrent()
        assertEquals(root.reference, requested.single())
        assertEquals(listOf(root), controller.state.value.path)
        assertEquals(root.reference, controller.state.value.rows.single().parentReference)
        controller.enter(child)
        runCurrent()
        controller.enter(grandchild)
        runCurrent()
        controller.back()
        runCurrent()
        assertEquals(listOf(root, child), controller.state.value.path)
        controller.back()
        runCurrent()
        controller.back()
        assertEquals(null, controller.state.value.sourceId)
    }

    @Test fun `index search uses saved source entries without invoking unsupported server search`() = runTest {
        var browsed = 0
        var searched: Pair<String, String>? = null
        val source = object : MediaSourceBrowser {
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
                browsed++
                return MediaSourcePage(emptyList())
            }
        }
        val torrent = MediaSourceEntry(MediaResourceRef("test", "release"), "Release", MediaSourceEntryKind.TORRENT)
        val controller = ResourceBrowserController(backgroundScope, torrent()) { id, query ->
            searched = id to query
            listOf(ResourcePreviewInput(torrent, "Season 2/01.mkv"))
        }
        controller.open("test", "NAS", source)
        runCurrent()
        assertTrue(controller.state.value.searchesIndex)
        controller.search("Season 2")
        runCurrent()
        assertEquals("test" to "Season 2", searched)
        assertEquals("Season 2/01.mkv", controller.state.value.rows.single().selectedFilePath)
        assertEquals(1, browsed)
        controller.search("")
        runCurrent()
        assertEquals(2, browsed)
    }

    @Test fun `late root resolution does not replace another source`() = runTest {
        val release = CompletableDeferred<Unit>()
        val source = object : MediaSourceBrowser {
            override suspend fun rootEntry() = withContext(NonCancellable) {
                release.await()
                MediaSourceEntry(MediaResourceRef("test", "root"), "Old", MediaSourceEntryKind.DIRECTORY)
            }
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?) = MediaSourcePage(emptyList())
        }
        val other = object : MediaSourceBrowser {
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?) = MediaSourcePage(emptyList())
        }
        val controller = ResourceBrowserController(backgroundScope, torrent())
        controller.open("test", "Old", source)
        runCurrent()
        controller.open("other", "New", other)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals("other", controller.state.value.sourceId)
        assertTrue(controller.state.value.path.isEmpty())
    }

    @Test
    fun `search-only source waits for user and preserves the complete keyword`() = runTest {
        var received: String? = null
        val source = object : MediaSourceBrowser {
            override val supportsRootBrowse = false
            override val searchScope = MediaSourceSearchScope.SOURCE
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage = error("No root browsing")
            override suspend fun search(keyword: String, parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
                received = keyword
                return MediaSourcePage(listOf(entry("result")))
            }
        }
        val controller = ResourceBrowserController(backgroundScope, torrent())
        controller.open("test", "Test", source)
        runCurrent()
        assertTrue(controller.state.value.needsSearch)
        assertFalse(controller.state.value.loading)
        controller.search("  full keyword SP  ")
        runCurrent()
        assertEquals("  full keyword SP  ", received)
        assertEquals("result", controller.state.value.rows.single().entry.name)
    }

    @Test
    fun `late response from cancelled source cannot replace current folder`() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = object : MediaSourceBrowser {
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage = withContext(NonCancellable) {
                release.await()
                MediaSourcePage(listOf(entry("old")))
            }
        }
        val current = object : MediaSourceBrowser {
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?) = MediaSourcePage(listOf(entry("current", "new")))
        }
        val controller = ResourceBrowserController(backgroundScope, torrent())
        controller.open("test", "Old", old)
        runCurrent()
        controller.open("new", "New", current)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals("new", controller.state.value.sourceId)
        assertEquals("current", controller.state.value.rows.single().entry.name)
    }

    @Test
    fun `pagination merges stable entries and a repeated token reports failure`() = runTest {
        val source = object : MediaSourceBrowser {
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage = when (pageToken) {
                null -> MediaSourcePage(listOf(entry("one")), "2")
                "2" -> MediaSourcePage(listOf(entry("one"), entry("two")), "3")
                else -> MediaSourcePage(listOf(entry("bad")), "3")
            }
        }
        val controller = ResourceBrowserController(backgroundScope, torrent())
        controller.open("test", "Test", source)
        runCurrent()
        controller.more()
        runCurrent()
        assertEquals(listOf("one", "two"), controller.state.value.rows.map { it.entry.name })
        controller.more()
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertEquals(listOf("one", "two"), controller.state.value.rows.map { it.entry.name })
    }

    @Test
    fun `failed listing is distinct from an empty folder and can retry`() = runTest {
        var fail = true
        val source = object : MediaSourceBrowser {
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
                check(!fail) { "offline" }
                return MediaSourcePage(emptyList())
            }
        }
        val controller = ResourceBrowserController(backgroundScope, torrent())
        controller.open("test", "Test", source)
        runCurrent()
        assertNotNull(controller.state.value.error)
        fail = false
        controller.refresh()
        runCurrent()
        assertEquals(null, controller.state.value.error)
        assertTrue(controller.state.value.rows.isEmpty())
    }

    @Test
    fun `foreign source references are rejected`() = runTest {
        val source = object : MediaSourceBrowser {
            override suspend fun browse(parent: MediaResourceRef?, pageToken: String?) = MediaSourcePage(listOf(entry("wrong", "other")))
        }
        val controller = ResourceBrowserController(backgroundScope, torrent())
        controller.open("test", "Test", source)
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertTrue(controller.state.value.rows.isEmpty())
    }
}
