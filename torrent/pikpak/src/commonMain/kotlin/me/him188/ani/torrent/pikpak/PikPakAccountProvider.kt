/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.torrent.offline.OfflineDownloadAuthException
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.UnsafeScopedHttpClientApi

/**
 * Owns the single account's SDK client. Credentials do not depend on whether BT acceleration is enabled.
 * Both drive reads and the offline engine borrow this provider; the application owns its HTTP client.
 */
class PikPakAccountProvider(
    private val httpClient: HttpClient,
    private val credentials: StateFlow<PikPakCredentials?>,
    private val sessionStore: SessionStore,
) {
    @OptIn(UnsafeScopedHttpClientApi::class)
    constructor(
        scopedHttpClient: ScopedHttpClient,
        credentials: StateFlow<PikPakCredentials?>,
        sessionStore: SessionStore,
    ) : this(scopedHttpClient.borrowForever().client, credentials, sessionStore)

    private val mutex = Mutex()
    private var entry: Pair<PikPakCredentials, PikPakClient>? = null

    suspend fun accountScope(): String = withClient { scope, _ -> scope }

    /** Rejects an account mismatch before a file endpoint is called, including changes during login. */
    suspend fun <T> withClient(
        expectedAccountScope: String? = null,
        block: suspend (accountScope: String, client: PikPakClient) -> T,
    ): T {
        val (creds, client) = mutex.withLock {
            val current = credentials.value?.takeIf { it.isValid }
                ?: throw OfflineDownloadAuthException("PikPak account is not configured")
            val cached = entry
            if (cached != null && cached.first == current) cached else {
                val fresh = PikPakClient(
                    account = current.username,
                    password = current.password,
                    sessionStore = guardedStore(current.username),
                    httpClient = httpClient,
                )
                (current to fresh).also { entry = it }
            }
        }
        val session = client.login()
        val scope = session.sub.takeIf { it.isNotEmpty() }
            ?: throw OfflineDownloadAuthException("PikPak session has no account identity")
        checkAccount(creds.username)
        if (expectedAccountScope != null && expectedAccountScope != scope) {
            throw PikPakAccountChangedException()
        }
        val result = block(scope, client)
        checkAccount(creds.username)
        return result
    }

    private fun checkAccount(username: String) {
        if (credentials.value?.username != username) throw PikPakAccountChangedException()
    }

    // A late SDK refresh for the previous account must not overwrite the new account's token.
    private fun guardedStore(username: String): SessionStore = object : SessionStore {
        override suspend fun load(account: String): Session? {
            checkAccount(username)
            return sessionStore.load(account)
        }

        override suspend fun save(account: String, session: Session) {
            checkAccount(username)
            sessionStore.save(account, session)
        }

        override suspend fun clear(account: String) {
            checkAccount(username)
            sessionStore.clear(account)
        }
    }
}

class PikPakAccountChangedException : Exception("This resource belongs to a different PikPak account")
