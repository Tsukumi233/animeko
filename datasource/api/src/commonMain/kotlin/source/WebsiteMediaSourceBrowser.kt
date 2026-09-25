/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.datasources.api.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.datasources.api.Media

/** Adapts the site's subject/playlist structure without applying automatic episode matching. */
class WebsiteMediaSourceBrowser(private val source: MediaSource) : MediaSourceBrowser, MediaSourceResourceFactory {
    override val supportsRootBrowse: Boolean get() = false
    override val searchScope: MediaSourceSearchScope get() = MediaSourceSearchScope.SOURCE

    @Serializable
    private data class Locator(
        val subject: BrowseSubject,
        val channelIndex: Int? = null,
        val channel: BrowseChannel? = null,
        val episode: BrowseEpisode? = null,
    )

    private fun reference(value: Locator): MediaResourceRef {
        val identity = Json.encodeToString(listOf(
            value.subject.url,
            value.channelIndex?.toString().orEmpty(),
            value.episode?.url.orEmpty(),
        ))
        return MediaResourceRef(source.mediaSourceId, identity, Json.encodeToString(value))
    }

    private fun decode(ref: MediaResourceRef): Locator {
        require(ref.sourceId == source.mediaSourceId && ref.version == 1)
        return Json.decodeFromString<Locator>(ref.locator).also { require(reference(it).resourceId == ref.resourceId) }
    }

    override suspend fun search(keyword: String, parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
        require(parent == null && pageToken == null)
        return MediaSourcePage(source.searchSubjects(keyword).map {
            MediaSourceEntry(reference(Locator(it)), it.name, MediaSourceEntryKind.SUBJECT)
        })
    }

    override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
        require(pageToken == null)
        val value = decode(requireNotNull(parent) { "Search for a subject first" })
        require(value.episode == null) { "A video is not a container" }
        return MediaSourcePage(if (value.channel == null) {
            source.browseSubject(value.subject).mapIndexed { index, channel ->
                MediaSourceEntry(
                    reference(Locator(value.subject, index, channel.copy(episodes = emptyList()))),
                    channel.label ?: channel.name ?: "${index + 1}",
                    MediaSourceEntryKind.PLAYLIST,
                    parent = parent,
                )
            }
        } else {
            val channel = source.browseSubject(value.subject).getOrNull(requireNotNull(value.channelIndex))
                ?: error("Playlist no longer exists")
            channel.episodes.map { episode ->
                MediaSourceEntry(
                    reference(value.copy(channel = channel.copy(episodes = emptyList()), episode = episode)),
                    episode.name, MediaSourceEntryKind.VIDEO,
                    suggestedEpisodeSort = episode.episodeSort, parent = parent,
                )
            }
        })
    }

    override suspend fun createMedia(reference: MediaResourceRef, request: MediaFetchRequest): Media {
        val value = decode(reference)
        return source.createMedia(value.subject, requireNotNull(value.channel).name,
            requireNotNull(value.episode), request.episodeSort)
    }
}
