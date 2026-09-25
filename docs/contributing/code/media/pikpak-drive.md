# PikPak 账号与只读网盘访问

`torrent/pikpak` 的 `PikPakAccountProvider` 为网盘来源与 BT 加速引擎共享单账号 SDK client。应用注册一个 provider，凭据流只取决于账号是否可用；BT 加速启用状态由 `PikPakOfflineDownloadEngine` 的凭据流控制，不控制网盘浏览。

长期文件引用保存登录会话返回的账号 `sub` 与 `fileId`。每次读取前检查账号作用域，账号发生变化时抛出 `PikPakAccountChangedException`，不能把旧账号的文件 ID 用于新账号。SDK 会话持久化也需要在应用设置更新事务内核对账号，防止旧请求覆盖新账号的 refresh token。

`PikPakDriveAccess` 提供以下只读能力：

- `list` 按 `parentId`、页大小与不透明页令牌调用 SDK `listFilesPaged`。根目录的 `parentId` 是空字符串；空的下一页令牌表示结束。
- `search` 复用 SDK 0.4.3 `searchFiles`。该函数读取指定文件夹的全部页，在客户端按文件名进行忽略大小写的包含匹配，不递归搜索子目录，也不是服务端全盘搜索。UI 应表达“当前文件夹”，资源库全局搜索由索引层承担。
- `resolve` 每次通过 `fileId` 获取详情并解析新的签名地址。返回临时 URL、下载请求头、有效期、大小和内容 hash。下载续传不能仅靠 `fileId` 和大小证明内容未变；无 hash 时必须采用传输层的可靠验证或拒绝不安全续传。

目录浏览、搜索、详情读取均不调用创建任务、创建目录、移动或删除接口。网盘文件下载到本机也是读取远端原文件。BT 加速引擎独立维护自己的工作目录和任务生命周期。

`PikPakDriveAccessTest` 使用 SDK 与 Ktor MockEngine 验证分页参数、目录搜索范围、账号隔离、临时链接刷新和只读 HTTP 操作。这些测试不代表真实账号的服务端行为已经完成验收。现有 `PikPakLiveSmokeTest` 包含离线任务写入，不能用来做网盘只读验收。

`PikPakPagingCompat` 是针对固定 SDK 0.4.3 的内部 API 兼容边界。SDK 的 `listFilesPaged` 和 `FileListPage` 为 Kotlin internal，因此仅此文件使用可见性抑制；对外不暴露这些类型。它保留 SDK 的鉴权、限速、重试和验证码处理，避免为单页浏览复制 HTTP 认证逻辑。升级 SDK 时必须验证该桥接的编译与分页测试；公开分页 API 后可以直接采用公开接口。

## 应用接入

`PikPakAccountServices` 在 Windows、Android 与 iOS 的平台模块中注册为单例，向网盘与加速引擎共享 `PikPakAccountProvider`。网盘凭据不检查加速开关，加速引擎单独接收受开关控制的凭据流。refresh token 的读取核对用户名，写入在设置更新事务中再次核对用户名。

`PikPakMediaSource` 同时实现人工浏览、资源转换、播放能力和下载能力。来源配置仅含名称与优先级，账号来自共享配置；长期引用保存账号作用域、文件 ID 和供离线展示的元数据。候选转换只读取已保存的引用，播放与下载准备阶段才查询真实文件并取得临时 URL。`fetch` 本身不扫描网盘，确认关联的候选由应用关联层统一提供。

下载通过通用 HTTP 能力返回请求头、内容 hash 和可刷新标志，并在来源层应用 PikPak 下载并发设置。任务管理器无需判断网盘品牌。来源实例关闭不关闭共享账号或删除任何网盘文件。
