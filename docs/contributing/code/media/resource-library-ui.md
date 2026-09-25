# 资源页面

主导航的资源页由 `ResourceLibraryScreen` 提供，包含我的资源、来源、下载三个页签。下载页使用 `DownloadManagementScreen`，来源设置使用现有数据源设置入口。

`ResourceLibraryViewModel` 协调本地文件选择、来源配置、资源索引与关联确认。Android 文件选择保留 document/tree 的只读授权；Desktop 文件选择保存文件 URI。WebDAV 和 SMB 的凭证单独保存，PikPak 使用已有账号配置。

`ResourceBrowserController` 负责来源浏览、分页与搜索。搜索范围来自 `MediaSourceBrowser.searchScope`；只支持搜索的来源显示关键字提示。BT 发布通过 `TorrentResourceBrowser` 展开完整文件路径，非视频文件不能关联。切换来源、目录或搜索请求时取消旧任务，并用请求标识阻止迟到结果覆盖页面。

文件选择跨页面保留。确认窗口通过已有条目搜索获取候选，再加载包含 SP 的完整剧集信息，使用 `ResourceAssociationPreviewBuilder` 提议对应关系。用户可以逐个修改、按显示顺序分配或跳过文件。只有所有参与关联的文件都指定剧集后，才调用 `AssociateResourcesUseCase` 提交。关闭窗口会取消条目加载，重新打开的窗口具有独立请求标识。

打开确认窗口时按资源身份和完整文件路径恢复已保存的绑定与跳过决定。全部文件都跳过时也可以提交。重新确认某个文件只清除该文件的跳过决定，同一个 BT 发布内其他路径的决定保持独立。

`ResourceBrowserControllerTest` 覆盖分页、取消、来源隔离和错误恢复；`ResourceBrowserScreenTest` 使用合成输入验证搜索与文件选择，并比较窄屏和宽屏截图。截图宿主提供 Material Surface，使颜色和内容绘制与页面宿主一致。这些测试不验证系统选择器或原生播放器。
