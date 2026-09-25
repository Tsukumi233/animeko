/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.test.runTest
import org.openani.mediamp.source.SeekableInputMediaData
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalResourceAccessTest {
    @Test
    fun `unicode paths survive enumeration and URI round trip`() = runTest {
        val directory = Files.createTempDirectory("ani-local").toFile()
        try {
            val nested = directory.resolve("剧集 # 1").apply { mkdir() }
            val video = nested.resolve("片名 01 %.mkv").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val access = SystemLocalResourceAccess()
            val root = access.stat(directory.absolutePath)
            assertTrue(root.isDirectory)
            val child = access.list(root.uri).single()
            assertEquals(nested.name, child.name)
            val file = access.list(child.uri).single()
            assertEquals(video.name, file.name)
            assertEquals(4L, file.size)
            assertEquals(file, access.stat(file.uri))
            val data = access.createMediaDataProvider(file.uri).open(this) as SeekableInputMediaData
            try {
                data.createInput(EmptyCoroutineContext).use { input ->
                    input.seekTo(2)
                    val result = ByteArray(2)
                    assertEquals(2, input.read(result))
                    assertContentEquals(byteArrayOf(3, 4), result)
                }
            } finally {
                data.close()
            }
            access.releaseReadPermission(file.uri)
            assertTrue(video.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `missing directory and invalid URI do not appear empty`() = runTest {
        val directory = Files.createTempDirectory("ani-local").toFile()
        try {
            val access = SystemLocalResourceAccess()
            assertEquals(emptyList(), access.list(directory.absolutePath))
            assertFailsWith<NoSuchFileException> { access.list(directory.resolve("missing").absolutePath) }
            assertFailsWith<IllegalArgumentException> { access.stat("content://documents/1") }
            assertFailsWith<IllegalArgumentException> { access.createMediaDataProvider(directory.absolutePath) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
