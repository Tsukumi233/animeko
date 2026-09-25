package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaMatch

/** 先发布本地确认的归属；自动查询失败时，已发布的候选仍然有效。 */
internal fun confirmedResourceSource(
    confirmed: suspend () -> List<Media>,
    automatic: suspend () -> SizedSource<MediaMatch>,
): SizedSource<MediaMatch> = object : SizedSource<MediaMatch> {
    override val finished = MutableStateFlow(false)
    override val totalSize = flowOf<Int?>(null)
    override val results = flow {
        finished.value = false
        try {
            val saved = confirmed()
            val savedIds = saved.mapTo(HashSet()) { it.mediaId }
            saved.forEach { emit(MediaMatch(it, MatchKind.EXACT)) }
            automatic().results.collect { if (it.media.mediaId !in savedIds) emit(it) }
        } finally {
            finished.value = true
        }
    }
}
