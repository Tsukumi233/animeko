package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.datasources.api.source.MediaResourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceLibraryIndexSearchTest {
    @Test fun `search isolates sources and preserves each exact torrent file path`() = runBlocking {
        val database = createTestAniDatabase()
        try {
            val dao = database.resourceLibraryDao()
            val library = ResourceLibraryRepository(dao)
            fun resource(id: String, source: String, name: String, kind: String) = LibraryResourceEntity(
                id, source, id, Json.encodeToString(MediaResourceRef(source, id, "stable-$id")), name, kind,
            )
            dao.upsertResource(resource("video", "nas", "SHOW 01.mkv", "VIDEO"))
            dao.upsertResource(resource("foreign", "other", "SHOW 02.mkv", "VIDEO"))
            val release = resource("torrent", "nas", "Show collection", "TORRENT")
            dao.upsertResource(release)
            dao.upsertBinding(LibraryEpisodeBindingEntity(release.id, "nas", 1, 11, "{}", "Season A/01.mkv"))
            dao.upsertSuggestion(LibraryMatchSuggestionEntity(release.id,
                """{"paths":["Season B/01.mkv"],"rows":[{"selectedFilePath":"Season C/01.mkv"}],"version":1}""", true))
            val matches = library.searchIndexed("nas", "show")
            assertEquals(4, matches.size)
            assertTrue(matches.all { it.resource.sourceId == "nas" })
            assertEquals(listOf("Season A/01.mkv", "Season B/01.mkv", "Season C/01.mkv"),
                matches.filter { it.resource.id == "torrent" }.map { it.selectedFilePath })
            assertEquals("Season B/01.mkv", library.searchIndexed("nas", "season b").single().selectedFilePath)
            assertEquals("stable-torrent", library.decodeReference(matches.last().resource).locator)
            assertTrue(library.searchIndexed("nas", "").isEmpty())
        } finally { database.close() }
    }

    @Test fun `unknown suggestion format cannot silently erase torrent file choices`() = runBlocking<Unit> {
        val database = createTestAniDatabase()
        try {
            val dao = database.resourceLibraryDao()
            dao.upsertResource(LibraryResourceEntity("torrent", "nas", "release", "{}", "Show", "TORRENT"))
            dao.upsertSuggestion(LibraryMatchSuggestionEntity("torrent", """{"version":2}"""))
            assertFailsWith<IllegalArgumentException> { ResourceLibraryRepository(dao).searchIndexed("nas", "show") }
        } finally { database.close() }
    }
}
