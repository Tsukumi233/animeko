package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadCreationConcurrencyTest {
    private val episode = EpisodeMetadata("Episode", EpisodeSort(1), EpisodeSort(1))

    @Test
    fun `duplicate waits for persistence while unrelated creation proceeds`() = runTest {
        val firstCache = testDownload(1)
        val otherCache = testDownload(2)
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val storage = DownloadTestStorage().apply {
            create = { media, _, _ ->
                calls += media.mediaId
                if (media.mediaId == firstCache.origin.mediaId) {
                    listFlow.value += firstCache // The UI can observe a preparation placeholder.
                    gate.await()
                    firstCache
                } else otherCache
            }
        }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)
        val first = async { manager.createDownload(firstCache.origin, firstCache.metadata, episode, storage) }
        runCurrent()
        val duplicate = async { manager.createDownload(firstCache.origin, firstCache.metadata, episode, storage) }
        val other = async { manager.createDownload(otherCache.origin, otherCache.metadata, episode, storage) }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(duplicate.isCompleted)
        assertSame(otherCache, other.await())
        gate.complete(Unit)
        assertSame(firstCache, first.await())
        assertSame(firstCache, duplicate.await())
        assertEquals(listOf(firstCache.origin.mediaId, otherCache.origin.mediaId), calls)
    }

    @Test
    fun `cancelling one waiter does not cancel shared creation`() = runTest {
        val cache = testDownload(1)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val storage = DownloadTestStorage().apply { create = { _, _, _ -> calls++; gate.await(); cache } }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)
        val owner = async { manager.createDownload(cache.origin, cache.metadata, episode, storage) }
        runCurrent()
        val waiter = async { manager.createDownload(cache.origin, cache.metadata, episode, storage) }
        runCurrent()
        waiter.cancelAndJoin()
        assertTrue(owner.isActive)
        gate.complete(Unit)
        assertSame(cache, owner.await())
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled creator releases pending identity and permits a later retry`() = runTest {
        val cache = testDownload(1)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val storage = DownloadTestStorage().apply { create = { _, _, _ -> calls++; gate.await(); cache } }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)
        val owner = async { manager.createDownload(cache.origin, cache.metadata, episode, storage) }
        runCurrent()
        val waiter = async { runCatching { manager.createDownload(cache.origin, cache.metadata, episode, storage) } }
        runCurrent()
        owner.cancelAndJoin()
        assertTrue(waiter.await().isFailure)
        gate.complete(Unit)
        assertSame(cache, manager.createDownload(cache.origin, cache.metadata, episode, storage))
        assertEquals(2, calls)
    }
}
