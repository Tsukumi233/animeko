# 站点与 BT 源的手动浏览

`MediaSourceBrowser` 列出来源提供的原始结果，不执行番剧名称或当前剧集匹配。
Selector 的自动匹配开关不控制这一能力。调用方通过 `MediaSourceResourceFactory` 将明确选择转换为候选，保存剧集关联。

`WebsiteMediaSourceBrowser` 复用 `searchSubjects`、`browseSubject` 和 `createMedia`。
搜索使用用户输入的完整关键字，浏览层次为条目、线路、视频。线路没有独立 URL 时，身份由条目 URL 和线路位置组成；
线路名称只用于展示和既有选源偏好，同名线路可分别浏览。追加剧集不改变线路身份，站点重排线路可能改变位置身份。
每次打开线路都读取当前列表；视频引用保存原始条目、线路名称、视频页面与标题，创建候选不依赖列表仍然包含该视频。
这些来源没有根目录与可用的分页接口，能力声明与返回分页标记明确表达这一限制。

RSS 使用引擎的 `allMediaList`，Mikan 直接调用 RSS 关键字接口，Dmhy 直接调用话题列表接口。
手动查询不截短关键字，不经 Bangumi 映射，也不按剧集、名称或类别过滤发布条目。
RSS 含 `{page}` 的配置和 Dmhy 按原始结果空页结束分页；Mikan RSS 只提供单页响应。
HTTP 错误与缺少预期列表结构的响应作为失败传播，正常空频道或空表才是空结果。

`TorrentMediaSourceReferences` 将 BT 发布条目标记为 `TORRENT` 容器，保存原始 `Media` 和 `mediaId`。
引用只接受磁力链接或长期 torrent 下载 URL，不保存播放器临时 URL；解析时验证来源、资源 ID 和版本。
种子文件枚举由下载/种子层提供，不能将发布条目本身伪装成已选中的视频文件。
