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
