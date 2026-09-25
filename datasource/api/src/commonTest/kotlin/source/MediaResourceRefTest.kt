package me.him188.ani.datasources.api.source

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MediaResourceRefTest {
    @Test
    fun `round trip preserves provider locator and scope`() {
        val reference = MediaResourceRef("nas-one", "folder/03.mkv", "目录/第三话 #3.mkv")
        assertEquals(reference, Json.decodeFromString<MediaResourceRef>(Json.encodeToString(reference)))
        assertNotEquals(reference, reference.copy(sourceId = "nas-two"))
    }

    @Test
    fun `missing stable identity is rejected`() {
        assertFailsWith<IllegalArgumentException> { MediaResourceRef("", "file") }
        assertFailsWith<IllegalArgumentException> { MediaResourceRef("source", " ") }
        assertFailsWith<IllegalArgumentException> { MediaResourceRef("source", "file", version = 0) }
    }

    @Test
    fun `reference format defaults allow older saved locators`() {
        val reference = Json.decodeFromString<MediaResourceRef>("""{"sourceId":"nas","resourceId":"01.mkv"}""")
        assertEquals("01.mkv", reference.locator)
        assertEquals(1, reference.version)
    }
}
