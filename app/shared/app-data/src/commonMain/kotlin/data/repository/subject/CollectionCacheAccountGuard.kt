/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes account changes and cache writes while allowing concurrent network requests. */
class CollectionCacheAccountGuard {
    private val mutex = Mutex()
    private var generation = 0L

    suspend fun snapshot(): Long = mutex.withLock { generation }

    suspend fun <T> commit(expectedGeneration: Long, block: suspend () -> T): T = mutex.withLock {
        if (generation != expectedGeneration) throw CancellationException("Collection cache account changed")
        block()
    }

    /** Sanitization and token publication happen together; new requests cannot capture an intermediate account. */
    suspend fun changeAccount(block: suspend () -> Unit) = mutex.withLock {
        generation++
        block()
    }
}
