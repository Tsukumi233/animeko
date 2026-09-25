/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.data.models.preference.ProxyConfig
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave

/** Used by the manager's single shared collector; unchanged sources retain their active inputs. */
internal class MediaSourceInstancePool(
    private val create: (MediaSourceSave, ProxyConfig?) -> MediaSourceInstance?,
) {
    private data class Entry(val save: MediaSourceSave, val proxy: ProxyConfig?, val instance: MediaSourceInstance)
    private var entries = emptyMap<String, Entry>()

    fun update(saves: List<MediaSourceSave>, proxy: ProxyConfig?): List<MediaSourceInstance> {
        val next = linkedMapOf<String, Entry>()
        try {
            for (save in saves) {
                val previous = entries[save.instanceId]
                val entry = if (previous?.save == save && previous.proxy == proxy) previous
                else create(save, proxy)?.let { Entry(save, proxy, it) }
                if (entry != null) next[save.instanceId] = entry
            }
        } catch (error: Throwable) {
            next.values.filter { entries[it.save.instanceId] !== it }.forEach { it.instance.close() }
            throw error
        }
        val removed = entries.values.filter { next[it.save.instanceId] !== it }
        entries = next
        removed.forEach { it.instance.close() }
        return next.values.map { it.instance }
    }
}
