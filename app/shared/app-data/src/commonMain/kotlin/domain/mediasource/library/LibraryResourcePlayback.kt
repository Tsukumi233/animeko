/* Copyright (C) 2026 OpenAni and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package me.him188.ani.app.domain.mediasource.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaAssociation
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.unwrapCached
import kotlin.coroutines.cancellation.CancellationException

enum class LibraryResourcePlaybackError { MISSING_BINDING, SOURCE_UNAVAILABLE, INVALID_REFERENCE, SELECTION_REJECTED }

/** Resolves only the confirmed tuple; names and episode numbers are not identity evidence. */
suspend fun ResourceLibraryRepository.resolveForPlayback(resourceId: String, subjectId: Int, episodeId: Int): Media? {
    val resource = dao.findResource(resourceId) ?: return null
    val binding = dao.bindingsForSubject(resource.sourceId, subjectId).first()
        .singleOrNull { it.resourceId == resourceId && it.episodeId == episodeId } ?: return null
    val media = decodeMedia(binding).unwrapCached()
    require(media.mediaSourceId == resource.sourceId)
    val location = if (media.download is ResourceLocation.SourceResource) {
        val reference = decodeReference(resource)
        require(reference.sourceId == resource.sourceId)
        ResourceLocation.SourceResource(reference)
    } else media.download
    return media.copy(
        download = location,
        association = MediaAssociation(subjectId.toString(), listOf(episodeId.toString()),
            binding.selectedFilePath?.let { mapOf(episodeId.toString() to it) } ?: emptyMap()),
    )
}

/** One explicit request per page. Failure leaves selection untouched and never enables automatic selection. */
class LibraryResourcePlaybackRequest(
    private val episodeId: Int,
    private val resolve: suspend () -> Media?,
    private val sourceExists: suspend (String) -> Boolean,
) {
    private val mutableError = MutableStateFlow<LibraryResourcePlaybackError?>(null)
    val error = mutableError.asStateFlow()
    private var attempted = false
    private var previousSelector: MediaSelector? = null

    /** Metadata refresh can rebuild the selector; retain the user's latest choice without rewriting preference. */
    suspend fun selectForEpisode(currentEpisodeId: Int, selector: MediaSelector): Boolean {
        val previous = previousSelector
        val handled = selectForEpisode(currentEpisodeId) { media ->
            selector.select(media) || selector.selected.value == media
        }
        if (handled) {
            if (previous != null && previous !== selector && selector.selected.value == null) {
                previous.selected.value?.let { selector.selectTemporarily(it) }
            }
            previousSelector = selector
        }
        return handled
    }

    suspend fun selectForEpisode(currentEpisodeId: Int, select: suspend (Media) -> Boolean): Boolean {
        if (currentEpisodeId != episodeId) return false
        if (attempted) return true
        try {
            val media = resolve()
            when {
                media == null -> mutableError.value = LibraryResourcePlaybackError.MISSING_BINDING
                !sourceExists(media.mediaSourceId) -> mutableError.value = LibraryResourcePlaybackError.SOURCE_UNAVAILABLE
                !select(media) -> mutableError.value = LibraryResourcePlaybackError.SELECTION_REJECTED
            }
            attempted = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableError.value = LibraryResourcePlaybackError.INVALID_REFERENCE
            attempted = true
        }
        return true
    }
}
