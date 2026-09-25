/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** App-owned scans of saved roots. Foreground requests join work without owning its lifetime. */
class ResourceLibraryScanCoordinator(
    private val scope: CoroutineScope,
    private val roots: suspend () -> List<LibraryScanRootEntity>,
    private val findRoot: suspend (String) -> LibraryScanRootEntity?,
    private val scan: suspend (LibraryScanRootEntity) -> Boolean,
    private val now: () -> Long = ::currentTimeMillis,
    private val staleAfter: Duration = 15.minutes,
    private val retryAfter: Duration = 1.minutes,
    parallelism: Int = 2,
) {
    private val lock = Mutex()
    private val permits = Semaphore(parallelism)
    private val tasks = mutableMapOf<String, Deferred<Result<Boolean?>>>()
    private val lastAttempt = mutableMapOf<String, Long>()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val activeRootIds = active.asStateFlow()

    /** One foreground event examines only explicitly saved roots; no timers or recurring jobs. */
    fun refreshStaleOnForeground(): Job = scope.launch {
        coroutineScope {
            roots().forEach { root -> launch {
                try { request(root.id, force = false) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    // The scanner persists its failure on the root; one failed root does not stop others.
                }
            } }
        }
    }

    /** Null means absent or fresh. Manual refresh joins an in-flight scan, otherwise bypasses age and cooldown. */
    suspend fun request(rootId: String, force: Boolean = true): Boolean? {
        val task = lock.withLock {
            tasks[rootId]?.let { return@withLock it }
            val root = findRoot(rootId) ?: return null
            val time = now()
            if (!force && !isStale(root, time)) return null
            if (!force && lastAttempt[rootId]?.let { time >= it && time - it < retryAfter.inWholeMilliseconds } == true) return null
            lateinit var created: Deferred<Result<Boolean?>>
            created = scope.async(start = CoroutineStart.LAZY) {
                try {
                    Result.success(permits.withPermit {
                        // Resolve again after queueing: a removed or relocated root cannot use stale scope data.
                        val current = findRoot(rootId) ?: return@withPermit null
                        if (!force && !isStale(current, now())) return@withPermit null
                        scan(current)
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    withContext(NonCancellable) {
                        lock.withLock {
                            if (tasks[rootId] === created) {
                                tasks.remove(rootId)
                                lastAttempt[rootId] = now()
                                active.value = tasks.keys.toSet()
                            }
                        }
                    }
                }
            }
            tasks[rootId] = created
            lastAttempt[rootId] = time
            active.value = tasks.keys.toSet()
            created.start()
            created
        }
        return task.await().getOrThrow()
    }

    /** Explicit cancellation affects every waiter for this root and waits for scanner cleanup. */
    suspend fun cancel(rootId: String) {
        val task = lock.withLock { tasks[rootId]?.also { it.cancel() } }
        withContext(NonCancellable) {
            task?.join()
            lock.withLock {
                if (task != null && tasks[rootId] === task) {
                    tasks.remove(rootId)
                    lastAttempt[rootId] = now()
                    active.value = tasks.keys.toSet()
                }
            }
        }
    }

    private fun isStale(root: LibraryScanRootEntity, time: Long): Boolean {
        if (root.activeScanToken != null) return true
        val completed = root.lastCompletedMillis ?: return true
        return time < completed || time - completed >= staleAfter.inWholeMilliseconds
    }
}
