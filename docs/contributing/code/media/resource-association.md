# 资源关联

`AssociateResourcesUseCase` 是用户确认资源对应关系的提交入口。调用方提供已选中的 `MediaSourceEntry`、条目 ID 和剧集 ID；BT 发布还必须提供枚举所得的完整 `selectedFilePath`。

## 提交过程

1. 校验视频与剧集对应关系：一个视频文件只对应一集，BT 发布中的不同文件可以分别对应多集。同一集可以拥有不同资源。
2. 通过 `SubjectCollectionRepository.librarySubjectCollectionFlow` 获取条目和完整剧集列表。关联页面不受日常剧集类型筛选影响，包含 SP；缓存过期时仍可离线使用，刷新失败不终止缓存观察。
3. 验证每个剧集确实属于指定条目，并由来源的 `MediaSourceResourceFactory` 创建候选。条目搜索或文件名解析所得的建议不能代替此身份校验。
4. 所有候选准备完成后，`ResourceLibraryRepository.associateBatch` 在同一个数据库事务中保存整批关联。准备失败时没有任何关联被写入；准备过程中获取的公共条目信息可以留在缓存中。

返回的候选携带 `MediaAssociation` 和精确的剧集范围，调用方可将当前集的候选交给现有 `MediaSelector.select`。提交本身不修改选源偏好、收藏状态或观看进度。

手工重新确认同一文件时，事务会替换该文件的旧归属。BT 文件可以整批交换对应剧集；没有参与修改的文件和其他视频版本保留各自的关联。

## BT 文件身份

`TorrentResourceBrowser` 读取种子元数据，返回原始发布引用、原始 `Media` 和文件完整路径。它不请求视频内容句柄；取消或完成时仅关闭没有其他使用者的元数据会话。

关联记录保留原始 `mediaId` 与磁力链或种子地址。文件路径独立存储在 `LibraryEpisodeBindingEntity.selectedFilePath`，读取候选时汇总到 `MediaAssociation.selectedFilePaths`。同名文件依靠完整路径区分；关联不能通过修改 `mediaId` 为每个文件创建新种子身份。

缺少指定文件时由解析器报告失败，不按文件名或集号重新猜测。下载与播放共用此文件选择信息。

## 指定资源播放

`AniNavigator.navigateEpisodeDetails(..., libraryResourceId = resource.id)` 携带资源库行 ID。
路由按条目和剧集去重；资源 ID 不改变播放历史的身份，进度恢复仍使用现有扩展。
`resolveForPlayback` 只读取该资源与指定条目、剧集的已确认绑定，并保留 BT 发布 ID 和精确内部文件路径。
来源引用使用资源库当前保存的定位信息，不使用标题推断或临时 URL。

`LibraryResourcePlaybackRequest` 通过 `MediaSelector.select` 提交一次手动选择，沿用来源偏好记忆。
请求存在时初始自动选源被抑制；缺失绑定、来源被移除或损坏的引用显示错误，不选择其他候选。
读取视频时的网络与权限错误由现有解析器处理。切到其他剧集恢复普通自动选源和 fallback。
条目信息刷新重建选择器时，保留用户最近选择的视频并使用临时选择接口，避免重复写入偏好。
显式资源路由的元数据请求包含所有已知剧集，因此被日常筛选隐藏的 SP 也可打开。

已确认候选的空字幕语言列表表示元数据未知，不据此套用无字幕隐藏或语言排除。
已知语言、来源、画质偏好和平台字幕兼容性仍按普通候选处理。
来源查询失败仅影响当前会话的回退；用户记住的来源保留，直到用户手动选择其他来源或显式清除偏好。
