/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.compose.runtime.Immutable
import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.schedule.AnimeRecurrence
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectTmdbArt
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.persistent.database.ProtoConverters
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.hours

/**
 * @see SubjectInfo
 */
@Entity(
    tableName = "subject_collection",
    indices = [
        Index(value = ["lastUpdated"], unique = false, orders = [Index.Order.DESC]),
    ],
)
data class SubjectCollectionEntity(
    @PrimaryKey val subjectId: Int,

    // SubjectInfo
    val name: String,
    val nameCn: String,
    val summary: String,
    val nsfw: Boolean,
    val imageLarge: String,
    /**
     * 列表用封面, 服务端下发. 为空表示这条记录写入时服务端还没有这个字段.
     * @since 6.2.0
     */
    @ColumnInfo(defaultValue = "")
    val imageThumb: String = "",
    /**
     * 会在获取剧集列表时使用, 用于验证缓存的剧集数目是否正确
     */
    val totalEpisodes: Int,
    val airDate: PackedDate,
    @field:TypeConverters(ProtoConverters.StringList::class)
    val aliases: List<String>,
    @field:TypeConverters(ProtoConverters.TagList::class)
    val tags: List<Tag>,
    @Embedded(prefix = "collection_stats_")
    val collectionStats: SubjectCollectionStats,
    @Embedded(prefix = "rating_")
    val ratingInfo: RatingInfo,
    val completeDate: PackedDate,
    // SubjectCollectionInfo

    @Embedded(prefix = "self_rating_")
    val selfRatingInfo: SelfRatingInfo,
    val collectionType: UnifiedCollectionType,

    /**
     * @since 4.1.0-alpha01
     */
    @Embedded(prefix = "recurrence_")
    val recurrence: AnimeRecurrence?,

    /**
     * @since 5.0.0
     */
    @Embedded(prefix = "relations_")
    val relations: SubjectRelations = SubjectRelations.Empty,

    /**
     * @see SubjectInfo.tmdbArt
     */
    @field:TypeConverters(ProtoConverters.SubjectTmdbArtConverter::class)
    val tmdbArt: SubjectTmdbArt? = null,

    /**
     * 此条目最后被修改的时间 (如修改收藏状态). 与服务器同步.
     */
    @ColumnInfo(defaultValue = "0")
    val lastUpdated: Long,
    /**
     * 此条目从 bangumi 服务器上查询到的时间. 用于判断是否需要自动刷新
     */
    @ColumnInfo(defaultValue = "0")
    val lastFetched: Long,
    @ColumnInfo(defaultValue = "0")
    val cachedStaffUpdated: Long,
    @ColumnInfo(defaultValue = "0")
    val cachedCharactersUpdated: Long,
)

@Immutable // don't change field name, stored in database
data class SubjectRelations(
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.IntList::class)
    val seriesMainSubjectIds: List<Int>,
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.StringList::class)
    val seriesMainSubjectNames: List<String>,
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.IntList::class)
    val sequelSubjects: List<Int>,
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.StringList::class)
    val sequelSubjectNames: List<String>,
) {
    companion object {
        val Empty = SubjectRelations(
            seriesMainSubjectIds = emptyList(),
            seriesMainSubjectNames = emptyList(),
            sequelSubjects = emptyList(),
            sequelSubjectNames = emptyList(),
        )
    }
}

@Dao
interface SubjectCollectionDao {
    @Upsert
    suspend fun upsert(item: SubjectCollectionEntity)

    @Upsert
    @Transaction
    suspend fun upsert(item: List<SubjectCollectionEntity>)

    @Query("""UPDATE subject_collection SET collectionType = :collectionType, lastUpdated = :lastUpdated WHERE subjectId = :subjectId""")
    suspend fun updateType(
        subjectId: Int,
        collectionType: UnifiedCollectionType,
        lastUpdated: Long = currentTimeMillis(),
    )

    @Transaction
    suspend fun delete(subjectId: Int) = deleteByIds(listOf(subjectId))

    /**
     * 清除收藏状态。资源库关联所需的公开条目和剧集元数据保留，其余缓存级联删除。
     */
    @Transaction
    suspend fun deleteByIds(subjectIds: List<Int>) {
        val retained = libraryReferencedSubjects(subjectIds)
        deleteUnreferencedByIds(subjectIds)
        upsert(retained.map { it.copy(
            collectionType = UnifiedCollectionType.NOT_COLLECTED,
            selfRatingInfo = SelfRatingInfo.Empty,
            lastUpdated = 0,
            lastFetched = 0,
        ) })
        clearEpisodeCollectionState(subjectIds)
    }

    @Query("""SELECT * FROM subject_collection WHERE subjectId IN (:subjectIds)
        AND EXISTS (SELECT 1 FROM library_episode_binding b WHERE b.subjectId = subject_collection.subjectId)""")
    suspend fun libraryReferencedSubjects(subjectIds: List<Int>): List<SubjectCollectionEntity>

    @Query("""DELETE FROM subject_collection WHERE subjectId IN (:subjectIds)
        AND NOT EXISTS (SELECT 1 FROM library_episode_binding b WHERE b.subjectId = subject_collection.subjectId)""")
    suspend fun deleteUnreferencedByIds(subjectIds: List<Int>)

    @Query("""UPDATE episode_collection SET selfCollectionType = 'NOT_COLLECTED', lastFetched = 0
        WHERE subjectId IN (:subjectIds)""")
    suspend fun clearEpisodeCollectionState(subjectIds: List<Int>)

    @Query("""SELECT subjectId FROM subject_collection WHERE :type IS NULL OR collectionType = :type""")
    suspend fun cachedSubjectIds(type: UnifiedCollectionType? = null): List<Int>

    /** 清空成功刷新的分页快照；后续页面到达前，保留同账号的评分与剧集观看状态。 */
    @Transaction
    suspend fun invalidateCollectionPage(type: UnifiedCollectionType?) {
        val ids = cachedSubjectIds(type)
        val retained = libraryReferencedSubjects(ids)
        deleteUnreferencedByIds(ids)
        upsert(retained.map { it.copy(collectionType = UnifiedCollectionType.NOT_COLLECTED, lastFetched = 0) })
    }

    /**
     * 将所有条目的 [SubjectCollectionEntity.lastFetched] 置 0, 使所有本地缓存视为已过期,
     * 下次进入收藏页 (分页刷新) 或条目页时会从服务端重新拉取. 不删除本地数据.
     */
    @Query("""UPDATE subject_collection SET lastFetched = 0""")
    suspend fun resetAllLastFetched()

    @Transaction
    suspend fun deleteAll(type: UnifiedCollectionType) = deleteByIds(cachedSubjectIds(type))

    @Transaction
    suspend fun deleteAll() = deleteByIds(cachedSubjectIds())

    /**
     * Retrieves a paginated list of `SubjectCollectionEntity` items, optionally filtered by type.
     *
     * @param collectionTypes Optional filter for the `type` of items. If `null`, all items are retrieved.
     * @param limit Specifies the maximum number of items to retrieve.
     * @param offset Defines the starting position within the result set, allowing for pagination.
     * @return A `Flow` of a list of `SubjectCollectionEntity` items.
     */
    @Query(
        """
    SELECT * FROM subject_collection 
    WHERE collectionType != 'NOT_COLLECTED'
    AND (collectionType IN (:collectionTypes))
    ORDER BY lastUpdated DESC
    LIMIT :limit
    OFFSET :offset
    """,
    )
    fun filterMostRecentUpdated(
        collectionTypes: List<UnifiedCollectionType>,
        limit: Int,
        offset: Int = 0,
    ): Flow<List<SubjectCollectionEntity>>

    @Query(
        """
    SELECT * FROM subject_collection 
    WHERE collectionType != 'NOT_COLLECTED'
    ORDER BY lastUpdated DESC
    LIMIT :limit
    OFFSET :offset
    """,
    )
    fun mostRecentUpdated(
        limit: Int,
        offset: Int = 0,
    ): Flow<List<SubjectCollectionEntity>>

    /**
     * Retrieves a paginated list of `SubjectCollectionEntity` items, optionally filtered by type.
     *
     * @param collectionType Optional filter for the `type` of items. If `null`, all items are retrieved. If empty, no item will be returned.
     * @return A `Flow` of a list of `SubjectCollectionEntity` items.
     */
    @Query(
        """
        select * from subject_collection 
        where (collectionType != 'NOT_COLLECTED' AND (:collectionType IS NULL OR collectionType = :collectionType))
        AND (:includeNsfw OR NOT nsfw)
        order by lastUpdated DESC, subjectId DESC
        """,
    )
    @Transaction
    fun filterByCollectionTypePaging(
        collectionType: UnifiedCollectionType? = null,
        includeNsfw: Boolean,
    ): PagingSource<Int, SubjectCollectionAndEpisodes>

    @Query("""SELECT * FROM subject_collection WHERE subjectId = :subjectId""")
    fun findById(subjectId: Int): Flow<SubjectCollectionEntity?>

    @Query("""SELECT * FROM subject_collection WHERE subjectId IN (:subjectIds)""")
    fun filterByIds(subjectIds: IntArray): Flow<List<SubjectCollectionEntity>>

    @Query(
        """
        SELECT sc.subjectId FROM subject_collection sc WHERE NOT EXISTS (
            SELECT ec.lastFetched FROM episode_collection ec 
            WHERE (ec.subjectId = sc.subjectId) 
                AND (CAST(unixepoch('now', 'subsecond') * 1000 AS int) - ec.lastFetched > :cacheExpiry)
        )
        """,
    )
    fun subjectIdsWithValidEpisodeCollection(cacheExpiry: Long = 1.hours.inWholeMilliseconds): Flow<List<Int>>

    @Query(
        """
        SELECT COALESCE(MAX(lastFetched), 0) FROM subject_collection
        WHERE collectionType != 'NOT_COLLECTED'AND ((:type IS NULL) OR (collectionType = :type))
        """,
    )
    suspend fun lastFetched(type: UnifiedCollectionType?): Long

    @Query(
        """
    UPDATE subject_collection 
    SET 
        self_rating_score = COALESCE(:score, self_rating_score), 
        self_rating_comment = COALESCE(:comment, self_rating_comment), 
        self_rating_tags = COALESCE(:tags, self_rating_tags), 
        self_rating_isPrivate = COALESCE(:private, self_rating_isPrivate)
    WHERE subjectId = :subjectId
""",
    )
    suspend fun updateRating(subjectId: Int, score: Int?, comment: String?, tags: List<String>?, private: Boolean?)

    /**
     * 只包含保存在数据库的, 可能不完整
     */
    @Query("""SELECT COUNT(*) FROM subject_collection WHERE (collectionType != 'NOT_COLLECTED' AND (:collectionType IS NULL OR collectionType = :collectionType))""")
    fun countCollected(collectionType: UnifiedCollectionType?): Flow<Int>

    @Query("""UPDATE subject_collection SET cachedStaffUpdated = :time, cachedCharactersUpdated = :time WHERE subjectId = :subjectId""")
    suspend fun updateCachedRelationsUpdated(subjectId: Int, time: Long = currentTimeMillis())

    @Query(
        """
        SELECT sc.subjectId FROM subject_collection sc
        WHERE collectionType != 'NOT_COLLECTED'
        AND (collectionType IN (:collectionTypes))
        """,
    )
    fun subjectIdsByCollectionType(collectionTypes: List<UnifiedCollectionType>): Flow<List<Int>>

    @Query(
        """
        SELECT sc.nameCn FROM subject_collection sc
        WHERE collectionType != 'NOT_COLLECTED'
        AND (collectionType IN (:collectionTypes))
        """,
    )
    fun subjectNamesCnByCollectionType(collectionTypes: List<UnifiedCollectionType>): Flow<List<String>>
}

suspend inline fun SubjectCollectionDao.deleteAll(type: UnifiedCollectionType?) {
    if (type == null) {
        deleteAll()
    } else {
        deleteAll(type)
    }
}

data class SubjectCollectionAndEpisodes(
    @Embedded
    val collection: SubjectCollectionEntity,
    @Relation(
        entity = EpisodeCollectionEntity::class,
        parentColumn = "subjectId",
        entityColumn = "subjectId",
    )
    val episodesOfAnyType: List<EpisodeCollectionEntity>,
) {
    override fun toString(): String {
        return "SubjectCollectionAndEpisodes(collection.nameCn=${collection.nameCn}, episodes.size=${episodesOfAnyType.size})"
    }
}

fun SubjectCollectionDao.filterMostRecentUpdated(
    collectionTypes: List<UnifiedCollectionType>?,
    limit: Int,
    offset: Int = 0,
): Flow<List<SubjectCollectionEntity>> = if (collectionTypes == null) {
    mostRecentUpdated(limit, offset)
} else {
    filterMostRecentUpdated(collectionTypes, limit, offset)
}

fun SubjectCollectionDao.filterMostRecentUpdated(
    collectionType: UnifiedCollectionType? = null,
    limit: Int,
): Flow<List<SubjectCollectionEntity>> = filterMostRecentUpdated(listOfNotNull(collectionType), limit)
