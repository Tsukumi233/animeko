package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.LibraryScanEntryEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.mediasource.library.libraryPlaybackTestMedia
import me.him188.ani.app.domain.mediasource.library.resolveForPlayback
import me.him188.ani.datasources.api.topic.ResourceLocation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SystemLocalRelocationTest {
    @Test fun `moved native folder retains association by exact relative path without rewriting files`() = runBlocking {
        val directory = Files.createTempDirectory("library-relocation-")
        val database = createTestAniDatabase()
        try {
            val old = Files.createDirectories(directory.resolve("old").resolve("中 文"))
            val bytes = "original readonly video".encodeToByteArray()
            Files.write(old.resolve("01.mp4"), bytes)
            val access = SystemLocalResourceAccess()
            val library = ResourceLibraryRepository(database.resourceLibraryDao())
            val source = LocalFileMediaSource("disk", access, { emptyList() })
            val entry = source.entry(old.resolve("01.mp4").toUri().toString())
            val resource = library.associate(entry, 1, 11, libraryPlaybackTestMedia(mediaId = "stable", mediaSourceId = "disk",
                download = ResourceLocation.SourceResource(entry.reference)))
            val rootEntry = source.entry(directory.resolve("old").toUri().toString())
            val root = LibraryScanRootEntity("root", "disk", Json.encodeToString(rootEntry.reference), "old")
            library.dao.upsertScanRoot(root)
            library.dao.upsertScanEntry(LibraryScanEntryEntity(root.id, resource.id, "scan"))
            val replacement = directory.resolve("moved")
            Files.move(directory.resolve("old"), replacement)
            assertFalse(Files.exists(old))
            val useCase = LocalResourceRelocationUseCase(library, access)
            val plan = useCase.prepareDirectory(root.id, replacement.toUri().toString(), source)
            assertEquals("中 文/01.mp4", plan.items.single().relativePath)
            useCase.confirm(plan)
            assertContentEquals(bytes, Files.readAllBytes(replacement.resolve("中 文").resolve("01.mp4")))
            assertEquals("stable", library.resolveForPlayback(resource.id, 1, 11)!!.mediaId)
            assertEquals(resource.id, library.dao.findResource(resource.id)!!.id)
        } finally {
            database.close()
            check(directory.fileName.toString().startsWith("library-relocation-"))
            directory.toFile().deleteRecursively()
        }
    }
}
