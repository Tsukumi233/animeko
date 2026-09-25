package me.him188.ani.app.ui.resource

import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceLocationLabelTest {
    @Test fun `Windows file URI decodes spaces unicode and literal plus without losing drive`() {
        assertEquals("C:/Shows/番剧/Season 1/Ep+01.mkv", resourceLocationLabel("file:///C:/Shows/%E7%95%AA%E5%89%A7/Season%201/Ep+01.mkv"))
        assertEquals("D:/Shows/01.mkv", resourceLocationLabel("file:/D:/Shows/01.mkv"))
        assertEquals("C:/Shows/01.mkv", resourceLocationLabel("file://localhost/C:/Shows/01.mkv"))
    }

    @Test fun `network file locations retain server and full share path`() {
        assertEquals("//NAS/Shared shows/Season/01.mkv", resourceLocationLabel("file://NAS/Shared%20shows/Season/01.mkv"))
    }

    @Test fun `external storage uses complete document path rather than parent tree`() {
        assertEquals("primary/Anime/Season 1/01.mkv", resourceLocationLabel(
            "content://com.android.externalstorage.documents/tree/primary%3AAnime/document/primary%3AAnime%2FSeason%201%2F01.mkv"))
        assertEquals("ABCD-1234/Anime/Season 2", resourceLocationLabel(
            "content://com.android.externalstorage.documents/tree/ABCD-1234%3AAnime%2FSeason%202"))
    }

    @Test fun `downloads raw identifier shows its complete path`() {
        assertEquals("/storage/emulated/0/Download/Show 01.mkv", resourceLocationLabel(
            "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FShow%2001.mkv"))
    }

    @Test fun `opaque providers retain provider and full decoded document identity`() {
        assertEquals("com.example.cloud.documents / account:folder/item 1.mkv", resourceLocationLabel(
            "content://com.example.cloud.documents/tree/root/document/account%3Afolder%2Fitem%201.mkv"))
        assertEquals("com.android.providers.downloads.documents / msf:1234", resourceLocationLabel(
            "content://com.android.providers.downloads.documents/document/msf%3A1234"))
    }

    @Test fun `bare paths are unchanged and URI escaping is decoded only once`() {
        assertEquals("C:/Shows/A%20B.mkv", resourceLocationLabel("C:/Shows/A%20B.mkv"))
        assertEquals("C:/Shows/A%20B.mkv", resourceLocationLabel("file:///C:/Shows/A%2520B.mkv"))
        assertEquals("file:///C:/Shows/invalid%ZZ.mkv".removePrefix("file:///"), resourceLocationLabel("file:///C:/Shows/invalid%ZZ.mkv"))
    }
}
