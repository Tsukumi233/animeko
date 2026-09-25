package me.him188.ani.app.domain.mediasource.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalRelativePathTest {
    @Test fun `external storage paths respect volume and directory boundaries`() {
        assertEquals(listOf("中 文", "01.mp4"), externalStorageRelativePathSegments("primary:Movies", "primary:Movies/中 文/01.mp4"))
        assertEquals(listOf("Movies", "01.mp4"), externalStorageRelativePathSegments("primary:", "primary:Movies/01.mp4"))
        assertEquals(emptyList(), externalStorageRelativePathSegments("ABCD:Movies", "ABCD:Movies"))
        assertNull(externalStorageRelativePathSegments("primary:Movies", "primary:Movies2/01.mp4"))
        assertNull(externalStorageRelativePathSegments("primary:Movies", "ABCD:Movies/01.mp4"))
        assertNull(externalStorageRelativePathSegments("primary:Movies", "primary:Movies/../01.mp4"))
        assertNull(externalStorageRelativePathSegments("opaque", "opaque/01.mp4"))
    }
}
