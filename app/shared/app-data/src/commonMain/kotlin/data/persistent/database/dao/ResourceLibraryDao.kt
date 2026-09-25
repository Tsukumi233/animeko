/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 本机凭证与可导出的来源配置分开保存。 */
@Entity(tableName = "library_source_credentials")
data class LibrarySourceCredentialsEntity(
    @PrimaryKey val sourceId: String,
    val username: String,
    val password: String,
    val domain: String = "",
) {
    override fun toString(): String = "LibrarySourceCredentialsEntity(sourceId=$sourceId, credentials=<redacted>)"
}

/** 用户资源的索引；没有对收藏表的外键，取消追番不改变资源的归属。 */
@Entity(
    tableName = "library_resource",
    indices = [Index(value = ["sourceId", "resourceKey"], unique = true)],
)
data class LibraryResourceEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val resourceKey: String,
    val referenceJson: String,
    val name: String,
    val entryKind: String,
    val size: Long? = null,
    val modifiedTimeMillis: Long? = null,
    val available: Boolean = true,
)

@Entity(
    tableName = "library_episode_binding",
    primaryKeys = ["resourceId", "subjectId", "episodeId"],
    indices = [Index(value = ["subjectId"]), Index(value = ["sourceId", "subjectId"])],
)
data class LibraryEpisodeBindingEntity(
    val resourceId: String,
    val sourceId: String,
    val subjectId: Int,
    val episodeId: Int,
    val mediaJson: String,
    val selectedFilePath: String? = null,
)

@Entity(tableName = "library_scan_root", indices = [Index(value = ["sourceId"])])
data class LibraryScanRootEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val referenceJson: String,
    val name: String,
    val recursive: Boolean = true,
    val lastCompletedMillis: Long? = null,
    val activeScanToken: String? = null,
    val error: String? = null,
    val matchingRuleJson: String? = null,
)

/** 一个资源可以被多个重叠扫描根覆盖，各根的完成状态彼此独立。 */
@Entity(
    tableName = "library_scan_entry",
    primaryKeys = ["rootId", "resourceId"],
    indices = [Index(value = ["resourceId"])],
)
data class LibraryScanEntryEntity(
    val rootId: String,
    val resourceId: String,
    val scanToken: String,
    val present: Boolean = true,
)

@Entity(tableName = "library_match_suggestion")
data class LibraryMatchSuggestionEntity(
    @PrimaryKey val resourceId: String,
    val suggestionJson: String,
    val ignored: Boolean = false,
)

@Dao
abstract class ResourceLibraryDao {
    @Query("SELECT * FROM library_source_credentials WHERE sourceId = :sourceId")
    abstract suspend fun credentials(sourceId: String): LibrarySourceCredentialsEntity?

    @Upsert
    abstract suspend fun saveCredentials(credentials: LibrarySourceCredentialsEntity)

    @Query("DELETE FROM library_source_credentials WHERE sourceId = :sourceId")
    abstract suspend fun removeCredentials(sourceId: String)

    @Query("SELECT * FROM library_resource ORDER BY name, id")
    abstract fun resources(): Flow<List<LibraryResourceEntity>>

    @Query("SELECT * FROM library_resource WHERE sourceId = :sourceId ORDER BY name, id")
    abstract fun resourcesForSource(sourceId: String): Flow<List<LibraryResourceEntity>>

    @Query("SELECT * FROM library_resource WHERE id = :id")
    abstract suspend fun findResource(id: String): LibraryResourceEntity?

    @Query("SELECT * FROM library_resource WHERE sourceId = :sourceId AND resourceKey = :resourceKey")
    abstract suspend fun findResource(sourceId: String, resourceKey: String): LibraryResourceEntity?

    @Upsert
    abstract suspend fun upsertResource(resource: LibraryResourceEntity)

    @Upsert
    abstract suspend fun upsertBinding(binding: LibraryEpisodeBindingEntity)

    @Query("SELECT * FROM library_episode_binding ORDER BY subjectId, episodeId, resourceId")
    abstract fun bindings(): Flow<List<LibraryEpisodeBindingEntity>>

    @Query("SELECT * FROM library_episode_binding WHERE subjectId = :subjectId AND sourceId = :sourceId")
    abstract fun bindingsForSubject(sourceId: String, subjectId: Int): Flow<List<LibraryEpisodeBindingEntity>>

    @Query("SELECT * FROM library_episode_binding WHERE sourceId = :sourceId")
    abstract suspend fun bindingsForSourceSnapshot(sourceId: String): List<LibraryEpisodeBindingEntity>

    @Query("SELECT s.* FROM library_match_suggestion s INNER JOIN library_resource r ON r.id = s.resourceId WHERE r.sourceId = :sourceId")
    abstract suspend fun suggestionsForSourceSnapshot(sourceId: String): List<LibraryMatchSuggestionEntity>

    @Query("UPDATE library_scan_root SET activeScanToken = NULL WHERE sourceId = :sourceId")
    protected abstract suspend fun invalidateScans(sourceId: String)

    @Query("SELECT DISTINCT subjectId FROM library_episode_binding")
    abstract suspend fun associatedSubjectIds(): List<Int>

    @Query("DELETE FROM library_episode_binding WHERE resourceId = :resourceId AND subjectId = :subjectId AND episodeId = :episodeId")
    protected abstract suspend fun deleteBinding(resourceId: String, subjectId: Int, episodeId: Int)

    @Transaction
    open suspend fun removeBinding(resourceId: String, subjectId: Int, episodeId: Int) {
        deleteBinding(resourceId, subjectId, episodeId)
        findResource(resourceId)?.let { invalidateScans(it.sourceId) }
    }

    @Query("DELETE FROM library_episode_binding WHERE resourceId = :resourceId")
    protected abstract suspend fun removeBindingsForResource(resourceId: String)

    @Query("DELETE FROM library_resource WHERE id = :resourceId")
    protected abstract suspend fun deleteResource(resourceId: String)

    @Query("DELETE FROM library_scan_entry WHERE resourceId = :resourceId")
    protected abstract suspend fun deleteScanEntries(resourceId: String)

    @Query("DELETE FROM library_match_suggestion WHERE resourceId = :resourceId")
    abstract suspend fun removeSuggestion(resourceId: String)

    @Upsert
    abstract suspend fun upsertSuggestion(suggestion: LibraryMatchSuggestionEntity)

    @Query("SELECT * FROM library_match_suggestion")
    abstract fun suggestions(): Flow<List<LibraryMatchSuggestionEntity>>

    @Query("SELECT * FROM library_match_suggestion WHERE resourceId = :resourceId")
    abstract suspend fun findSuggestion(resourceId: String): LibraryMatchSuggestionEntity?

    @Query("SELECT * FROM library_scan_root ORDER BY name, id")
    abstract fun scanRoots(): Flow<List<LibraryScanRootEntity>>

    @Query("SELECT * FROM library_scan_root WHERE id = :rootId")
    abstract suspend fun findScanRoot(rootId: String): LibraryScanRootEntity?

    @Upsert
    abstract suspend fun upsertScanRoot(root: LibraryScanRootEntity)

    @Query("DELETE FROM library_scan_entry WHERE rootId = :rootId")
    protected abstract suspend fun removeScanEntries(rootId: String)

    @Query("DELETE FROM library_scan_root WHERE id = :rootId")
    protected abstract suspend fun deleteScanRoot(rootId: String)

    /** Forgetting a scan scope retains indexed resources, availability and confirmed bindings. */
    @Transaction
    open suspend fun removeScanRoot(rootId: String) {
        removeScanEntries(rootId)
        deleteScanRoot(rootId)
    }

    @Transaction
    open suspend fun beginScan(requested: LibraryScanRootEntity, token: String): LibraryScanRootEntity? {
        val current = findScanRoot(requested.id) ?: return null
        if (current.sourceId != requested.sourceId || current.referenceJson != requested.referenceJson) return null
        return current.copy(activeScanToken = token, error = null).also { upsertScanRoot(it) }
    }

    @Transaction
    open suspend fun updateMatchingRules(expected: LibraryScanRootEntity, rulesJson: String?): Boolean {
        if (findScanRoot(expected.id) != expected) return false
        upsertScanRoot(expected.copy(matchingRuleJson = rulesJson, activeScanToken = null, error = null))
        return true
    }

    @Upsert
    abstract suspend fun upsertScanEntry(entry: LibraryScanEntryEntity)

    @Transaction
    open suspend fun recordScanEntry(rootId: String, token: String, resource: LibraryResourceEntity): Boolean {
        if (findScanRoot(rootId)?.activeScanToken != token) return false
        upsertResource(resource.copy(available = true))
        upsertScanEntry(LibraryScanEntryEntity(rootId, resource.id, token))
        return true
    }

    @Transaction
    open suspend fun failScan(rootId: String, token: String, message: String) {
        val root = findScanRoot(rootId) ?: return
        if (root.activeScanToken == token) {
            upsertScanRoot(root.copy(activeScanToken = null, error = message))
        }
    }

    @Query("UPDATE library_scan_entry SET present = 0 WHERE rootId = :rootId AND scanToken != :token")
    protected abstract suspend fun markUnvisited(rootId: String, token: String)

    @Query("""
        UPDATE library_resource SET available = EXISTS(
            SELECT 1 FROM library_scan_entry WHERE resourceId = library_resource.id AND present = 1
        ) WHERE id IN (SELECT resourceId FROM library_scan_entry WHERE rootId = :rootId)
    """)
    protected abstract suspend fun updateAvailability(rootId: String)

    @Transaction
    open suspend fun confirmBinding(resource: LibraryResourceEntity, binding: LibraryEpisodeBindingEntity) {
        require(binding.resourceId == resource.id && binding.sourceId == resource.sourceId)
        upsertResource(resource)
        upsertBinding(binding)
        removeSuggestion(resource.id)
        invalidateScans(resource.sourceId)
    }

    @Query("""
        DELETE FROM library_episode_binding WHERE resourceId = :resourceId
        AND (selectedFilePath IS :selectedFilePath OR (subjectId = :subjectId AND episodeId = :episodeId))
    """)
    protected abstract suspend fun removeConflictingFileBindings(
        resourceId: String, selectedFilePath: String?, subjectId: Int, episodeId: Int,
    )

    @Transaction
    open suspend fun confirmBindings(
        resources: List<LibraryResourceEntity>,
        bindings: List<LibraryEpisodeBindingEntity>,
        replaceFileBindings: Boolean = false,
        suggestions: List<LibraryMatchSuggestionEntity> = emptyList(),
    ) {
        val byId = resources.associateBy { it.id }
        require(bindings.all { byId[it.resourceId]?.sourceId == it.sourceId })
        require(suggestions.all { it.resourceId in byId })
        resources.forEach { upsertResource(it) }
        if (replaceFileBindings) {
            bindings.forEach { removeConflictingFileBindings(it.resourceId, it.selectedFilePath, it.subjectId, it.episodeId) }
        }
        bindings.forEach { upsertBinding(it) }
        resources.forEach { removeSuggestion(it.id) }
        suggestions.forEach { upsertSuggestion(it) }
        resources.map { it.sourceId }.distinct().forEach { invalidateScans(it) }
    }

    /** Snapshot comparison and commit share the Room transaction; concurrent manual or automatic decisions win. */
    @Transaction
    open suspend fun completeScanWithMatches(
        expectedRoot: LibraryScanRootEntity,
        expectedBindings: List<LibraryEpisodeBindingEntity>,
        expectedSuggestions: List<LibraryMatchSuggestionEntity>,
        expectedResources: List<LibraryResourceEntity>,
        bindings: List<LibraryEpisodeBindingEntity>,
        suggestions: List<LibraryMatchSuggestionEntity>,
        completedMillis: Long,
        ruleError: String?,
    ): Boolean {
        val token = expectedRoot.activeScanToken ?: return false
        if (findScanRoot(expectedRoot.id) != expectedRoot) return false
        if (bindingsForSourceSnapshot(expectedRoot.sourceId).toSet() != expectedBindings.toSet()) return false
        if (suggestionsForSourceSnapshot(expectedRoot.sourceId).toSet() != expectedSuggestions.toSet()) return false
        for (resource in expectedResources) if (findResource(resource.id) != resource) return false
        val resources = expectedResources.associateBy { it.id }
        require(bindings.all { resources[it.resourceId]?.sourceId == expectedRoot.sourceId && it.sourceId == expectedRoot.sourceId })
        require(suggestions.all { it.resourceId in resources })
        if (bindings.any { candidate -> expectedBindings.any { current ->
                (current.resourceId == candidate.resourceId && current.selectedFilePath == candidate.selectedFilePath) ||
                    (current.subjectId == candidate.subjectId && current.episodeId == candidate.episodeId)
            } }) return false
        require(bindings.map { it.subjectId to it.episodeId }.distinct().size == bindings.size)
        bindings.forEach { upsertBinding(it) }
        suggestions.forEach { upsertSuggestion(it) }
        markUnvisited(expectedRoot.id, token)
        updateAvailability(expectedRoot.id)
        upsertScanRoot(expectedRoot.copy(lastCompletedMillis = completedMillis, activeScanToken = null, error = ruleError))
        return true
    }

    /** 仅完整成功且仍是当前扫描的结果可以判定缺失；取消和失败不调用此方法。 */
    @Transaction
    open suspend fun completeScan(rootId: String, token: String, completedMillis: Long): Boolean {
        val root = findScanRoot(rootId) ?: return false
        if (root.activeScanToken != token) return false
        markUnvisited(rootId, token)
        updateAvailability(rootId)
        upsertScanRoot(root.copy(lastCompletedMillis = completedMillis, activeScanToken = null, error = null))
        return true
    }

    /** 删除索引及关联；此 DAO 不持有文件系统或远程删除能力。 */
    @Transaction
    open suspend fun removeResource(resourceId: String) {
        findResource(resourceId)?.let { invalidateScans(it.sourceId) }
        removeBindingsForResource(resourceId)
        removeSuggestion(resourceId)
        deleteScanEntries(resourceId)
        deleteResource(resourceId)
    }
}
