/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.local

import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.platform.Context

actual fun createLocalResourceAccess(context: Context): LocalResourceAccess = object : LocalResourceAccess {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Local resource library is unavailable on iOS")
    override suspend fun stat(uri: String): LocalResourceEntry = unsupported()
    override suspend fun list(directoryUri: String): List<LocalResourceEntry> = unsupported()
    override suspend fun persistReadPermission(uri: String): Unit = unsupported()
    override suspend fun releaseReadPermission(uri: String): Unit = unsupported()
    override suspend fun createMediaDataProvider(uri: String): MediaDataProvider<*> = unsupported()
}
