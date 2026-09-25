/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Retains subscriptions for unchanged sources while adding and removing individual source results. */
internal fun <T : Any> combineMediaSourceResults(
    sources: Flow<List<MediaSourceFetchResult>>,
    transform: (MediaSourceFetchResult) -> Flow<T>,
): Flow<List<T>> = channelFlow {
    val updates = Channel<SourceListUpdate<T>>(Channel.BUFFERED)
    val jobs = mutableMapOf<MediaSourceFetchResult, Job>()
    val values = mutableMapOf<MediaSourceFetchResult, T>()
    var current = emptyList<MediaSourceFetchResult>()
    launch { sources.collect { updates.send(SourceListUpdate.Sources(it)) } }
    try {
        for (update in updates) {
            when (update) {
                is SourceListUpdate.Sources -> {
                    current = update.sources
                    for (removed in jobs.keys - current.toSet()) {
                        jobs.remove(removed)?.cancel()
                        values.remove(removed)
                    }
                    for (source in current) if (source !in jobs) {
                        jobs[source] = launch {
                            transform(source).collect { updates.send(SourceListUpdate.Value(source, it)) }
                        }
                    }
                }
                is SourceListUpdate.Value -> {
                    if (update.source !in jobs) continue
                    values[update.source] = update.value
                }
            }
            if (current.all { it in values }) send(current.map { values.getValue(it) })
        }
    } finally {
        updates.cancel()
    }
}

private sealed interface SourceListUpdate<out T> {
    data class Sources(val sources: List<MediaSourceFetchResult>) : SourceListUpdate<Nothing>
    data class Value<T>(val source: MediaSourceFetchResult, val value: T) : SourceListUpdate<T>
}
