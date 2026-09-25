package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.LibraryEpisodeBindingEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryRelocationSnapshot
import me.him188.ani.app.data.persistent.database.dao.LibraryResourceEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryScanEntryEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceMatchingRules
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.unwrapCached
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind

class LocalRelocationException(val reason: Reason, val resourceName: String? = null) : IllegalStateException(
    "${reason.name}${resourceName?.let { ": $it" }.orEmpty()}",
) {
    enum class Reason { NOT_VIDEO, NOT_DIRECTORY, UNSUPPORTED_PATH, OVERLAPPING_ROOTS, INCOMPLETE_DIRECTORY, DESTINATION_CONFLICT, CHANGED_SINCE_PREVIEW }
}

data class LocalRelocationItem(
    val resourceId: String,
    val oldName: String,
    val newName: String,
    val oldLocation: String,
    val newLocation: String,
    val relativePath: String?,
    val oldSize: Long?,
    val newSize: Long?,
)

/** Prepared choices require an explicit confirmation. Neither preparation nor commit modifies original files. */
class LocalResourceRelocationPlan internal constructor(
    val oldLocation: String,
    val newLocation: String,
    val directory: Boolean,
    val items: List<LocalRelocationItem>,
    internal val snapshot: LibraryRelocationSnapshot,
    internal val resources: List<LibraryResourceEntity>,
    internal val roots: List<LibraryScanRootEntity>,
    internal val scanEntries: List<LibraryScanEntryEntity>,
    internal val validation: List<LocalResourceEntry>,
    internal val refreshedBindings: List<LibraryEpisodeBindingEntity> = emptyList(),
)

class LocalResourceRelocationUseCase(
    private val library: ResourceLibraryRepository,
    private val access: LocalResourceAccess,
) {
    suspend fun prepareFile(resourceId: String, replacementUri: String, source: LocalFileMediaSource): LocalResourceRelocationPlan {
        val snapshot = library.dao.relocationSnapshot(source.mediaSourceId)
        val original = snapshot.resources.singleOrNull { it.id == resourceId }
            ?: throw LocalRelocationException(LocalRelocationException.Reason.CHANGED_SINCE_PREVIEW)
        if (original.entryKind != MediaSourceEntryKind.VIDEO.name) fail(LocalRelocationException.Reason.NOT_VIDEO, original.name)
        val oldReference = localReference(original)
        access.persistReadPermission(replacementUri)
        val target = source.entry(replacementUri)
        if (target.kind != MediaSourceEntryKind.VIDEO) fail(LocalRelocationException.Reason.NOT_VIDEO, target.name)
        val updated = original.relocated(target)
        val movedRoots = snapshot.roots.filter { decodeRoot(it) == oldReference }.map { it.relocated(target) }
        val finalRoots = snapshot.roots.associateBy { it.id } + movedRoots.associateBy { it.id }
        val retained = snapshot.scanEntries.filter { it.resourceId == original.id }.filter { entry ->
            val root = finalRoots.getValue(entry.rootId)
            access.relativePathSegments(decodeRoot(root).locator, target.reference.locator) != null ||
                decodeRoot(root) == target.reference
        }.map { it.copy(present = true) }
        checkDestinations(snapshot, listOf(updated), movedRoots)
        val targetStat = access.stat(target.reference.locator)
        requireSameEntry(target, targetStat)
        val refreshedBindings = snapshot.bindings.filter { it.resourceId == resourceId }.map { binding ->
            val previous = library.decodeMedia(binding)
            val request = MediaFetchRequest(binding.subjectId.toString(), binding.episodeId.toString(),
                subjectNames = listOfNotNull(previous.properties.subjectName),
                episodeSort = previous.episodeRange?.knownSorts?.singleOrNull() ?: EpisodeSort(""),
                episodeName = previous.properties.episodeName.orEmpty())
            val candidate = source.createMedia(target.reference, request).unwrapCached().copy(
                mediaId = previous.mediaId, episodeRange = previous.episodeRange, association = previous.association)
            binding.copy(mediaJson = Json.encodeToString(Media.serializer(), candidate))
        }
        requireSameEntry(target, access.stat(target.reference.locator))
        return LocalResourceRelocationPlan(oldReference.locator, target.reference.locator, false,
            listOf(original.preview(target, null)), snapshot, listOf(updated), movedRoots, retained,
            listOf(targetStat), refreshedBindings)
    }

    suspend fun prepareDirectory(rootId: String, replacementUri: String, source: LocalFileMediaSource): LocalResourceRelocationPlan {
        val snapshot = library.dao.relocationSnapshot(source.mediaSourceId)
        val root = snapshot.roots.singleOrNull { it.id == rootId }
            ?: throw LocalRelocationException(LocalRelocationException.Reason.CHANGED_SINCE_PREVIEW)
        val oldReference = decodeRoot(root)
        if (access.relativePathSegments(oldReference.locator, oldReference.locator) == null) {
            fail(LocalRelocationException.Reason.UNSUPPORTED_PATH, root.name)
        }
        for (other in snapshot.roots.filter { it.id != rootId }) {
            val otherLocation = decodeRoot(other).locator
            if (access.relativePathSegments(oldReference.locator, otherLocation) != null ||
                access.relativePathSegments(otherLocation, oldReference.locator) != null) {
                fail(LocalRelocationException.Reason.OVERLAPPING_ROOTS, other.name)
            }
        }
        access.persistReadPermission(replacementUri)
        val targetRoot = source.entry(replacementUri)
        if (targetRoot.kind != MediaSourceEntryKind.DIRECTORY) fail(LocalRelocationException.Reason.NOT_DIRECTORY, targetRoot.name)
        val rootStat = access.stat(targetRoot.reference.locator)
        requireSameEntry(targetRoot, rootStat)
        val validation = linkedMapOf(rootStat.uri to rootStat)
        val directoryCache = mutableMapOf<String, List<LocalResourceEntry>>()
        suspend fun resolve(parts: List<String>): MediaSourceEntry {
            var current = rootStat
            for ((index, part) in parts.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!current.isDirectory) fail(LocalRelocationException.Reason.INCOMPLETE_DIRECTORY, parts.joinToString("/"))
                val children = directoryCache.getOrPut(current.uri) { access.list(current.uri) }
                current = children.singleOrNull { it.name == part }
                    ?: fail(LocalRelocationException.Reason.INCOMPLETE_DIRECTORY, parts.joinToString("/"))
                if (index != parts.lastIndex && !current.isDirectory) fail(LocalRelocationException.Reason.INCOMPLETE_DIRECTORY, part)
                validation[current.uri] = current
            }
            return source.entry(current.uri).also { requireSameEntry(it, current) }
        }
        val mapped = snapshot.resources.mapNotNull { resource ->
            val relative = access.relativePathSegments(oldReference.locator, localReference(resource).locator)
                ?: return@mapNotNull null
            if (relative.isEmpty() || relative.any { it.isEmpty() || it == "." || it == ".." || '/' in it }) {
                fail(LocalRelocationException.Reason.UNSUPPORTED_PATH, resource.name)
            }
            val target = resolve(relative)
            if (resource.entryKind != MediaSourceEntryKind.VIDEO.name || target.kind != MediaSourceEntryKind.VIDEO ||
                resource.size == null || target.size != resource.size ||
                resource.modifiedTimeMillis?.takeIf { it > 0 }?.let { oldTime ->
                    target.modifiedTimeMillis?.takeIf { it > 0 }?.let { it != oldTime }
                } == true) fail(LocalRelocationException.Reason.INCOMPLETE_DIRECTORY, relative.joinToString("/"))
            Triple(resource, target, relative.joinToString("/"))
        }
        val movedIds = mapped.map { it.first.id }.toSet()
        if (snapshot.scanEntries.any { it.rootId == rootId && it.resourceId !in movedIds }) {
            fail(LocalRelocationException.Reason.INCOMPLETE_DIRECTORY, root.name)
        }
        if (snapshot.scanEntries.any { it.resourceId in movedIds && it.rootId != rootId }) {
            fail(LocalRelocationException.Reason.OVERLAPPING_ROOTS, root.name)
        }
        val resources = mapped.map { (original, target, _) -> original.relocated(target) }
        val roots = listOf(root.relocated(targetRoot))
        checkDestinations(snapshot, resources, roots)
        return LocalResourceRelocationPlan(oldReference.locator, targetRoot.reference.locator, true,
            mapped.map { (original, target, relative) -> original.preview(target, relative) }, snapshot, resources, roots,
            snapshot.scanEntries.filter { it.rootId == rootId && it.resourceId in movedIds }.map { it.copy(present = true) },
            validation.values.toList())
    }

    suspend fun confirm(plan: LocalResourceRelocationPlan) {
        for (expected in plan.validation) {
            currentCoroutineContext().ensureActive()
            if (access.stat(expected.uri) != expected) fail(LocalRelocationException.Reason.CHANGED_SINCE_PREVIEW, expected.name)
        }
        currentCoroutineContext().ensureActive()
        if (!library.relocate(plan.snapshot, plan.resources, plan.roots, plan.scanEntries, plan.refreshedBindings)) {
            fail(LocalRelocationException.Reason.CHANGED_SINCE_PREVIEW)
        }
    }

    private fun LibraryResourceEntity.relocated(target: MediaSourceEntry) = copy(
        resourceKey = target.reference.resourceId, referenceJson = Json.encodeToString(target.reference), name = target.name,
        entryKind = target.kind.name, size = target.size, modifiedTimeMillis = target.modifiedTimeMillis, available = true,
    )

    private fun LibraryScanRootEntity.relocated(target: MediaSourceEntry): LibraryScanRootEntity {
        val old = decodeRoot(this)
        val rules = matchingRuleJson?.let(ConfirmedResourceMatchingRules::decode)?.let { confirmed ->
            confirmed.copy(rules = confirmed.rules.map { rule ->
                require(rule.sourceId == sourceId && rule.parentResourceId == old.resourceId && rule.parentLocator == old.locator && rule.parentVersion == old.version)
                rule.copy(parentResourceId = target.reference.resourceId, parentLocator = target.reference.locator, parentVersion = target.reference.version)
            }).encode()
        }
        return copy(referenceJson = Json.encodeToString(target.reference), name = target.name, matchingRuleJson = rules,
            lastCompletedMillis = null, activeScanToken = null, error = null)
    }

    private fun LibraryResourceEntity.preview(target: MediaSourceEntry, relative: String?) = LocalRelocationItem(
        id, name, target.name, localReference(this).locator, target.reference.locator, relative, size, target.size,
    )

    private fun checkDestinations(snapshot: LibraryRelocationSnapshot, resources: List<LibraryResourceEntity>, roots: List<LibraryScanRootEntity>) {
        val updatedIds = resources.map { it.id }.toSet()
        val unchangedResources = snapshot.resources.filterNot { it.id in updatedIds }
        if (resources.any { target -> unchangedResources.any { old ->
                access.relativePathSegments(localReference(old).locator, localReference(target).locator)?.isEmpty() == true
            } }) fail(LocalRelocationException.Reason.DESTINATION_CONFLICT)
        val finalResources = unchangedResources + resources
        if (finalResources.map { it.resourceKey }.distinct().size != finalResources.size) fail(LocalRelocationException.Reason.DESTINATION_CONFLICT)
        val rootIds = roots.map { it.id }.toSet()
        val unchangedRoots = snapshot.roots.filterNot { it.id in rootIds }
        if (roots.any { target -> unchangedRoots.any { old ->
                access.relativePathSegments(decodeRoot(old).locator, decodeRoot(target).locator)?.isEmpty() == true
            } }) fail(LocalRelocationException.Reason.DESTINATION_CONFLICT)
        val finalRoots = unchangedRoots + roots
        if (finalRoots.map { it.referenceJson }.distinct().size != finalRoots.size) fail(LocalRelocationException.Reason.DESTINATION_CONFLICT)
    }

    private fun localReference(resource: LibraryResourceEntity): MediaResourceRef = library.decodeReference(resource).also {
        require(it.sourceId == resource.sourceId && it.version == 1 && it.resourceId == it.locator)
    }

    private fun decodeRoot(root: LibraryScanRootEntity): MediaResourceRef = Json.decodeFromString<MediaResourceRef>(root.referenceJson).also {
        require(it.sourceId == root.sourceId && it.version == 1 && it.resourceId == it.locator)
    }

    private fun requireSameEntry(entry: MediaSourceEntry, stat: LocalResourceEntry) {
        if (entry.reference.locator != stat.uri || entry.name != stat.name || entry.size != stat.size ||
            entry.modifiedTimeMillis != stat.modifiedMillis || (entry.kind == MediaSourceEntryKind.DIRECTORY) != stat.isDirectory) {
            fail(LocalRelocationException.Reason.CHANGED_SINCE_PREVIEW, entry.name)
        }
    }
    private fun fail(reason: LocalRelocationException.Reason, name: String? = null): Nothing = throw LocalRelocationException(reason, name)
}
