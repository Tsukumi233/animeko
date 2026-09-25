/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */
package me.him188.ani.app.domain.media.download.capability

import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.datasources.api.Media

/** A task identified by collection and episode cannot silently switch its selected file. */
fun MediaCache.requireCompatibleFileSelection(requested: Media, episodeId: String) {
    val path = requested.association?.selectedFilePaths?.get(episodeId) ?: return
    require(origin.association?.selectedFilePaths?.get(episodeId) == path) {
        "The episode already has a download with another file selection; remove it before selecting a different file"
    }
}
