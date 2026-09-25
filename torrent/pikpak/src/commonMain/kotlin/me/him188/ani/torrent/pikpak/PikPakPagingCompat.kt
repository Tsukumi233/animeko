/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.listFilesPaged

/**
 * Compatibility boundary for pikpak-kotlin 0.4.3: its public listFiles eagerly consumes every page,
 * while the page endpoint and response are internal. Keeping the endpoint in the SDK preserves
 * its authentication, rate limiting, retry and captcha handling. SDK upgrades must run
 * PikPakDriveAccessTest on the target platforms before changing the pinned version.
 */
internal suspend fun PikPakClient.listDriveFilesPage(
    parentId: String,
    pageSize: Int,
    pageToken: String,
): PikPakSdkFilePage {
    val page = listFilesPaged(parentId, pageSize, pageToken)
    return PikPakSdkFilePage(page.files, page.nextPageToken)
}

internal data class PikPakSdkFilePage(val files: List<FileStat>, val nextPageToken: String)
