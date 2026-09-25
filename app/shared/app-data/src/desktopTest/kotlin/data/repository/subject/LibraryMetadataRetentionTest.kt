/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.data.repository.subject

import androidx.paging.PagingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.persistent.database.dao.LibraryEpisodeBindingEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryResourceEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryMetadataRetentionTest {
    private val fixtures = SubjectCollectionRepositoryInvalidateTest()

    private suspend fun SubjectCollectionRepositoryInvalidateTest.Fixture.seed() {
        dao.upsert(listOf(fixtures.subject(1, 1, score = 8).copy(
            selfRatingInfo = SelfRatingInfo(8, "private note", listOf("private tag"), true),
        ), fixtures.subject(2, 1)))
        database.episodeCollection().upsert(listOf(fixtures.episode(1, 11, 1, 1),
            fixtures.episode(1, 12, 2, 1).copy(episodeType = EpisodeType.SP, sort = EpisodeSort("SP01")),
            fixtures.episode(2, 21, 1, 1)))
        database.resourceLibraryDao().confirmBinding(
            LibraryResourceEntity("video", "disk", "video", "{}", "video", "VIDEO"),
            LibraryEpisodeBindingEntity("video", "disk", 1, 11, "{}"),
        )
    }

    @Test
    fun `account cleanup retains public metadata and specials but clears all personal states`() = fixtures.runRepositoryTest {
        seed()
        accountGuard.changeAccount { dao.deleteAll() }
        val retained = requireNotNull(dao.findById(1).first())
        assertEquals("条目 1", retained.nameCn)
        assertEquals(SelfRatingInfo.Empty, retained.selfRatingInfo)
        assertEquals(UnifiedCollectionType.NOT_COLLECTED, retained.collectionType)
        assertEquals(0L, retained.lastFetched)
        assertNull(dao.findById(2).first())
        val episodes = database.episodeCollection().filterBySubjectId(1).first()
        assertEquals(setOf(11, 12), episodes.map { it.episodeId }.toSet())
        assertTrue(episodes.all { it.selfCollectionType == UnifiedCollectionType.NOT_COLLECTED })
        assertEquals(0, dao.countCollected(null).first())
        assertTrue(dao.mostRecentUpdated(20).first().isEmpty())
        assertTrue(dao.subjectIdsByCollectionType(UnifiedCollectionType.entries).first().isEmpty())
        val page = dao.filterByCollectionTypePaging(includeNsfw = true).load(PagingSource.LoadParams.Refresh(null, 20, false))
        assertTrue((page as PagingSource.LoadResult.Page).data.isEmpty())
        assertEquals(setOf(11, 12), repository.librarySubjectCollectionFlow(1).first().episodes.map { it.episodeId }.toSet())
    }

    @Test
    fun `refresh pagination preserves same account rating and episode progress`() = fixtures.runRepositoryTest {
        seed()
        dao.invalidateCollectionPage(UnifiedCollectionType.DOING)
        val retained = requireNotNull(dao.findById(1).first())
        assertEquals(8, retained.selfRatingInfo.score)
        assertEquals("private note", retained.selfRatingInfo.comment)
        assertTrue(database.episodeCollection().filterBySubjectId(1).first().all { it.selfCollectionType == UnifiedCollectionType.DONE })
        assertEquals(0, dao.countCollected(null).first())
        assertNull(dao.findById(2).first())
    }

    @Test
    fun `server missing episodes cannot delete bound subject metadata`() = fixtures.runRepositoryTest {
        seed()
        service.serverSubjects[1] = fixtures.serverSubject(1, episodeIds = listOf(11))
        repository.invalidateCache(listOf(1))
        assertEquals(setOf(11, 12), database.episodeCollection().listIdBySubjectId(1).first().toSet())
        database.episodeCollection().deleteAllBySubjectId(1)
        assertEquals(setOf(11, 12), database.episodeCollection().listIdBySubjectId(1).first().toSet())
        dao.delete(1)
        assertEquals(SelfRatingInfo.Empty, dao.findById(1).first()?.selfRatingInfo)
    }

    @Test
    fun `last removed binding allows normal metadata eviction`() = fixtures.runRepositoryTest {
        seed()
        database.resourceLibraryDao().removeBinding("video", 1, 11)
        dao.deleteByIds(listOf(1))
        assertNull(dao.findById(1).first())
        assertTrue(database.episodeCollection().listIdBySubjectId(1).first().isEmpty())
    }

    @Test
    fun `in flight old account response cannot repopulate sanitized collection metadata`() = fixtures.runRepositoryTest {
        seed()
        service.serverSubjects[1] = fixtures.serverSubject(1, score = 10, episodeIds = listOf(11))
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        coroutineScope {
            val oldRequest = async { runCatching { repository.invalidateCache(listOf(1)) } }
            service.firstFetch.await()
            accountGuard.changeAccount { dao.deleteAll() }
            gate.complete(Unit)
            assertTrue(oldRequest.await().exceptionOrNull() is CancellationException)
        }
        assertEquals(SelfRatingInfo.Empty, dao.findById(1).first()?.selfRatingInfo)
        assertFalse(database.episodeCollection().filterBySubjectId(1).first().any { it.selfCollectionType == UnifiedCollectionType.DONE })
    }
}
