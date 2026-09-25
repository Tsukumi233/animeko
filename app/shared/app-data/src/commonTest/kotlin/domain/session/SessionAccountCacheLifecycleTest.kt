/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.domain.session.auth.OAuthResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionAccountCacheLifecycleTest {
    private fun save(token: String) = TokenSave("refresh-$token", TokenSave.AccessTokens(null, token, Long.MAX_VALUE))
    private fun session(token: String) = AccessTokenSession(AccessTokenPair(token, Long.MAX_VALUE, null))

    @Test
    fun `new login and logout sanitize before publishing while token renewal bypasses sanitization`() = runTest {
        val store = MemoryDataStore(save("old"))
        val repository = TokenRepository(store)
        var calls = 0
        val manager = SessionManager(repository, backgroundScope, { error("No remote refresh") }, onAccountChange = { publish ->
            calls++
            assertEquals(if (calls == 1) "old" else "renewed", store.data.value.accessTokens?.aniAccessToken)
            publish()
        })
        manager.setSession(session("new"), "refresh-new")
        assertEquals(1, calls)
        assertEquals("new", store.data.value.accessTokens?.aniAccessToken)
        manager.setSession(session("renewed"), "refresh-renewed", isNewLogin = false)
        assertEquals(1, calls)
        manager.clearSession()
        assertEquals(2, calls)
        assertEquals(TokenSave.Initial, store.data.value)
    }

    @Test
    fun `failed sanitization cannot publish the new account`() = runTest {
        val store = MemoryDataStore(save("old"))
        val manager = SessionManager(TokenRepository(store), backgroundScope, { error("No remote refresh") },
            onAccountChange = { error("Storage unavailable") })
        assertFailsWith<IllegalStateException> { manager.setSession(session("new"), "refresh-new") }
        assertEquals(save("old"), store.data.value)
    }

    @Test
    fun `cancelled sanitization cannot publish the new account`() = runTest {
        val store = MemoryDataStore(save("old"))
        val started = CompletableDeferred<Unit>()
        val manager = SessionManager(TokenRepository(store), backgroundScope, { error("No remote refresh") },
            onAccountChange = { started.complete(Unit); awaitCancellation() })
        val job = launch { manager.setSession(session("new"), "refresh-new") }
        started.await()
        job.cancelAndJoin()
        assertEquals(save("old"), store.data.value)
    }

    @Test
    fun `backup import sanitizes changed token identity but keeps identical session caches`() = runTest {
        val store = MemoryDataStore(save("old"))
        var calls = 0
        val repository = TokenRepository(store, onRestoreSession = { publish ->
            calls++
            assertEquals("old", store.data.value.accessTokens?.aniAccessToken)
            publish()
        })
        repository.restoreFromTokenSave(save("old"))
        assertEquals(0, calls)
        repository.restoreFromTokenSave(save("new"))
        assertEquals(1, calls)
        assertEquals(save("new"), store.data.value)
    }

    @Test
    fun `in flight token renewal cannot resurrect a logged out or replaced account`() = runTest {
        for (replace in listOf(false, true)) {
            val store = MemoryDataStore(save("old"))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager = SessionManager(TokenRepository(store), backgroundScope, {
                started.complete(Unit)
                release.await()
                OAuthResult(session("renewed-old").tokens, 3600, "refresh-renewed-old")
            })
            val renewal = launch { manager.refreshSession() }
            started.await()
            if (replace) manager.setSession(session("new"), "refresh-new") else manager.clearSession()
            release.complete(Unit)
            renewal.join()
            assertEquals(if (replace) save("new") else TokenSave.Initial, store.data.value)
        }
    }
}
