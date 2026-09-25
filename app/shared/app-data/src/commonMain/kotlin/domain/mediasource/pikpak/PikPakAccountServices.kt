/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.pikpak

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.torrent.pikpak.PikPakAccountProvider
import me.him188.ani.torrent.pikpak.PikPakCredentials
import me.him188.ani.torrent.pikpak.PikPakDriveAccess
import me.him188.ani.torrent.pikpak.PikPakSessionStoreAdapter
import me.him188.ani.utils.ktor.ScopedHttpClient

/** One account session serves both existing drive files and optional BT acceleration. */
class PikPakAccountServices(settings: SettingsRepository, httpClient: ScopedHttpClient, scope: CoroutineScope) {
    val config = settings.pikpakConfig.flow
        .stateIn(scope, SharingStarted.Eagerly, PikPakConfig.Default)

    private val credentials = config.map { it.accountCredentials() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val accelerationCredentials = config.map { if (it.enabled) it.accountCredentials() else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val sessionStore = PikPakSessionStoreAdapter(
        readRefreshToken = { error("Account-scoped session access is required") },
        writeRefreshToken = { error("Account-scoped session access is required") },
        readRefreshTokenForAccount = { account ->
            config.value.let { if (it.username == account) it.refreshToken else "" }
        },
        writeRefreshTokenForAccount = { account, token ->
            settings.pikpakConfig.update {
                if (username == account) copy(refreshToken = token) else this
            }
        },
    )

    val accountProvider = PikPakAccountProvider(httpClient, credentials, sessionStore)
    val driveAccess = PikPakDriveAccess(accountProvider)
}

internal fun PikPakConfig.accountCredentials(): PikPakCredentials? =
    if (username.isNotEmpty() && (password.isNotEmpty() || refreshToken.isNotEmpty())) {
        PikPakCredentials(username, password)
    } else null
