/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.datasources.api.source

import kotlinx.serialization.json.Json
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation

/** Long-lived torrent release references preserve the provider's original media identity and metadata. */
object TorrentMediaSourceReferences {
    fun entry(media: Media): MediaSourceEntry {
        require(media.download is ResourceLocation.MagnetLink || media.download is ResourceLocation.HttpTorrentFile)
        return MediaSourceEntry(
            MediaResourceRef(media.mediaSourceId, media.mediaId, Json.encodeToString<Media>(media)),
            media.originalTitle, MediaSourceEntryKind.TORRENT,
        )
    }

    fun decode(reference: MediaResourceRef, sourceId: String = reference.sourceId): Media {
        require(reference.version == 1 && reference.sourceId == sourceId)
        return Json.decodeFromString<Media>(reference.locator).also {
            require(it.mediaSourceId == sourceId && it.mediaId == reference.resourceId)
            require(it.download is ResourceLocation.MagnetLink || it.download is ResourceLocation.HttpTorrentFile)
        }
    }
}
