/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.instance.createTestMediaSourceInstance
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.TestHttpMediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MediaSourceInstancePoolTest {
    private class RecordingSource(id: String, private val closed: MutableList<String>) : TestHttpMediaSource(mediaSourceId = id) {
        override fun close() { closed += mediaSourceId }
    }
    private fun save(id: String) = MediaSourceSave(id, id, FactoryId(id), true, MediaSourceConfig.Default)

    @Test
    fun `adding reordering and removing sources closes only removed instances`() {
        val closed = mutableListOf<String>()
        val pool = MediaSourceInstancePool { save, _ ->
            createTestMediaSourceInstance(RecordingSource(save.mediaSourceId, closed), instanceId = save.instanceId)
        }
        val first = pool.update(listOf(save("a")), null).single()
        val added = pool.update(listOf(save("a"), save("b")), null)
        assertSame(first, added.first())
        assertEquals(emptyList(), closed)
        val reordered = pool.update(listOf(save("b"), save("a")), null)
        assertSame(first, reordered.last())
        assertSame(added.last(), reordered.first())
        assertSame(first, pool.update(listOf(save("a")), null).single())
        assertEquals(listOf("b"), closed)
        pool.update(emptyList(), null)
        assertEquals(listOf("b", "a"), closed)
    }

    @Test
    fun `configuration replacement closes the previous instance and failed updates preserve existing sources`() {
        val closed = mutableListOf<String>()
        val created = mutableListOf<MediaSourceInstance>()
        val pool = MediaSourceInstancePool { save, _ ->
            check(save.instanceId != "fail")
            createTestMediaSourceInstance(RecordingSource(save.mediaSourceId, closed), instanceId = save.instanceId).also { created += it }
        }
        val original = pool.update(listOf(save("a")), null).single()
        val changed = save("a").copy(isEnabled = false)
        val replacement = pool.update(listOf(changed), null).single()
        assertNotSame(original, replacement)
        assertEquals(listOf("a"), closed)
        assertFailsWith<IllegalStateException> {
            pool.update(listOf(changed, save("temporary"), save("fail")), null)
        }
        assertEquals(listOf("a", "temporary"), closed)
        assertSame(replacement, pool.update(listOf(changed), null).single())
    }
}
