/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorAutoSelectUseCase
import org.koin.core.Koin

/**
 * 自动选择数据源
 *
 * @see MediaSelector
 */
class AutoSelectExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
    private val explicitSelection: suspend (Int, MediaSelector) -> Boolean = { _, _ -> false },
) : PlayerExtension("AutoSelect") {
    private val mediaSelectorAutoSelectUseCase: MediaSelectorAutoSelectUseCase by koin.inject()

    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope
    ) {
        backgroundTaskScope.launch("AutoSelect") {
            episodeSession.fetchSelectFlow.collectLatest { fetchSelect ->
                if (fetchSelect == null) return@collectLatest
                if (explicitSelection(episodeSession.episodeId, fetchSelect.mediaSelector)) return@collectLatest
                mediaSelectorAutoSelectUseCase(fetchSelect.mediaFetchSession, fetchSelect.mediaSelector)
            }
        }
    }

    companion object : EpisodePlayerExtensionFactory<AutoSelectExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): AutoSelectExtension {
            return AutoSelectExtension(context, koin)
        }
    }

    class Factory(private val explicitSelection: suspend (Int, MediaSelector) -> Boolean) : EpisodePlayerExtensionFactory<AutoSelectExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin) = AutoSelectExtension(context, koin, explicitSelection)
    }
}
