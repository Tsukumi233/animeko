/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.ui.foundation.DragAndDropContent
import me.him188.ani.app.ui.foundation.WindowDropCardContent
import me.him188.ani.app.ui.foundation.WindowDropHandler
import me.him188.ani.app.ui.foundation.WindowDropPreview
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.episode_drop_video_description
import me.him188.ani.app.ui.lang.episode_drop_video_supported_hint
import me.him188.ani.app.ui.lang.episode_drop_video_title
import me.him188.ani.app.ui.lang.episode_drop_video_unknown_name
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.name
import org.jetbrains.compose.resources.stringResource

/**
 * 播放页的窗口拖放处理者：选择本地视频并交给当前剧集的资源关联确认入口。
 *
 * 接管视频文件和本地目录，保留拖放顺序并去重，交给统一的批量关联入口。
 * 拖动阶段读不到内容时显示通用提示，松手后检查实际内容。
 */
class EpisodeVideoDropHandler(
    private val isDirectory: (SystemPath) -> Boolean = { runCatching { it.isDirectory() }.getOrDefault(false) },
    private val onSelect: (List<SystemPath>) -> Unit,
) : WindowDropHandler {
    private fun selectFiles(content: DragAndDropContent.FileList): List<SystemPath> = content.files
        .filter { DroppedFileMedia.isVideoFile(it) || isDirectory(it.inSystem) }
        .map { it.inSystem }.distinct()

    override fun onDragStarted(content: DragAndDropContent?): WindowDropPreview? {
        val file = when (content) {
            null -> null
            is DragAndDropContent.FileList -> selectFiles(content).firstOrNull() ?: return null
            is DragAndDropContent.PlainText, DragAndDropContent.Unsupported -> return null
        }
        return WindowDropPreview { EpisodeVideoDropCard(file) }
    }

    override fun onDrop(content: DragAndDropContent): Boolean {
        if (content !is DragAndDropContent.FileList) return false
        val files = selectFiles(content)
        if (files.isEmpty()) return false
        onSelect(files)
        return true
    }

    @Composable
    override fun supportedHint(): String = stringResource(Lang.episode_drop_video_supported_hint)
}

@Composable
fun rememberEpisodeVideoDropHandler(onSelect: (List<SystemPath>) -> Unit): EpisodeVideoDropHandler {
    val onSelectUpdated by rememberUpdatedState(onSelect)
    return remember { EpisodeVideoDropHandler { onSelectUpdated(it) } }
}

/**
 * 拖入视频文件时的卡片内容. [file] 为 `null` 表示拖动阶段读不到文件名.
 */
@Composable
private fun EpisodeVideoDropCard(file: SystemPath?) {
    WindowDropCardContent(
        icon = Icons.Rounded.PlayCircle,
        title = stringResource(Lang.episode_drop_video_title),
        subtitle = file?.name ?: stringResource(Lang.episode_drop_video_unknown_name),
        description = stringResource(Lang.episode_drop_video_description),
    )
}
