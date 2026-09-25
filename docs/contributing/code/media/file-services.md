# WebDAV 与 SMB 文件来源

`FileServiceMediaSource` 的统一工厂 ID 是 `file-service`，`FileServiceArguments.protocol` 区分 WebDAV 与 SMB。来源支持多个连接，并复用人工浏览、资源关联、选源、播放与下载能力接口。协议层位于 `domain/mediasource/fileservice`。

## 配置与资源身份

可导出的来源参数包含名称、协议、端点、共享目录、根路径、端口与优先级。WebDAV 端点是 HTTP(S) 根 URL，不能带用户信息、查询参数或 fragment；SMB 端点是主机名，共享目录与相对根路径单独保存。

`FileServiceCredentialProvider.get(sourceId)` 从本机凭据仓库提供用户名、密码与可选 SMB 域。凭据不会写入来源参数、资源引用、节点 ID 或导出文件，`FileServiceCredentials.toString()` 隐去全部字段。缺少凭据时使用匿名访问。

长期资源引用的 locator 包含相对路径及展示元数据。连接范围由协议、端点、共享目录、根路径、用户名与域的 SHA-256 标识，使用前核对范围，避免同一个来源 ID 更换服务器或账号后解释旧文件引用。密码更新不改变范围。候选构造使用已保存信息，不为每次播放扫描目录；确认关联的候选由应用关联层统一合并。

## WebDAV

- 使用 Ktor `PROPFIND Depth: 0/1` 查询属性和目录，读取 namespace 前缀不固定的 DAV XML。
- 服务端返回的 href 必须位于同一 origin 与已配置根路径之内；拒绝目录穿越、编码后的分隔符和越级目录结果。
- 鉴权使用 HTTP Basic；PROPFIND 不跟随重定向，鉴权失败、非法响应与服务错误均作为错误传播。
- 标准 PROPFIND 返回完整目录，没有虚构的分页令牌或全盘搜索。资源库可以对已索引内容搜索。
- HTTP 播放与下载返回凭据请求头。下载使用强 ETag 作为版本验证信息，并添加 `If-Match`。弱 ETag 或缺失 ETag 不被当作可靠的续传身份。

## SMB

JVM 与 Android 使用固定依赖 `com.hierynomus:smbj:0.14.0`。其 [SmbConfig](https://github.com/hierynomus/smbj/blob/v0.14.0/src/main/java/com/hierynomus/smbj/SmbConfig.java) 显式检测 Android，省略 GSS 认证工厂；默认 [BCSecurityProvider](https://github.com/hierynomus/smbj/blob/v0.14.0/src/main/java/com/hierynomus/security/bc/BCSecurityProvider.java) 使用轻量密码实现，避免依赖 Android 注册的 `BC` provider。项目 Android minSdk 为 27，满足适配中 `java.time` 的要求。其他原生平台报告不支持 SMB。

文件以 `GENERIC_READ` 与 `FILE_OPEN` 打开，适配器没有创建、写入、移动或删除远端文件的操作。SMBJ 负责 SMB2/SMB3 协商；目录列举使用 SDK 的完整目录读取，不暴露客户端伪分页。

播放通过 `SeekableInputMediaData` 按偏移读取，文件长度与偏移均使用 `Long`，不先复制整个文件。每个打开文件拥有独立连接；关闭输入或来源会断开连接并释放服务端句柄。异步读使用 `runInterruptible`，网络读与 socket 均设有限超时。

下载使用通用 `ByteRange` 能力。版本验证包含服务端文件 ID、change time、last write time 和长度，按连接范围隔离；每次重开句柄都验证版本与长度。该组合是服务端属性验证，不是内容的密码学摘要；属性缺失时返回未知身份，下载层应拒绝不安全续传。

## 验证边界

- `WebDavFileServiceAccessTest`：协议方法、鉴权、命名空间、中文/空格/保留字符、大文件长度、越界 href、重定向拒绝与取消。
- `FileServiceMediaSourceTest`：账号/服务器范围、凭据不进入引用、重开版本核对、超过 4 GiB 的输入位置与关闭传播。SMB 协议在这些测试中由假实现承担。
- `WebDavLoopbackTest`：本机真实 HTTP 服务，PROPFIND 与带 Range 的 GET，验证超过 4 GiB 偏移的真实网络读取。
- SMBJ 适配器已编译到 JVM。实际 SMB 服务器、Android 播放器 seek 与网络切换仍需运行验证，不能由假实现测试代替。
