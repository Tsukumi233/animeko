package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfirmedResourceSourceTest {
    @Test
    fun `confirmed candidate survives automatic failure`() = runTest {
        val saved = TestMediaList.first()
        val failure = IllegalStateException("offline")
        var caught: Throwable? = null
        val source = confirmedResourceSource({ listOf(saved) }, { throw failure })
        val result = source.results.catch { caught = it }.toList()
        assertEquals(listOf(MediaMatch(saved, MatchKind.EXACT)), result)
        assertSame(failure, caught)
        assertTrue(source.finished.first())
    }

    @Test
    fun `automatic identity duplicate cannot replace confirmed metadata`() = runTest {
        val saved = TestMediaList.first()
        val source = confirmedResourceSource({ listOf(saved) }, {
            object : SizedSource<MediaMatch> {
                override val results = TestMediaList.take(2).map { MediaMatch(it, MatchKind.FUZZY) }.asFlow()
                override val finished = flowOf(true)
                override val totalSize = flowOf(2)
            }
        })
        val result = source.results.toList()
        assertEquals(2, result.size)
        assertEquals(MatchKind.EXACT, result.first().kind)
        assertEquals(MatchKind.FUZZY, result.last().kind)
    }

    @Test
    fun `cancellation propagates`() = runTest {
        val source = confirmedResourceSource({ emptyList() }, { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { source.results.toList() }
    }
}
