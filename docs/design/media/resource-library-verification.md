# 资源库验收记录与复现

本文记录资源库功能的自动化与真实运行证据，以及尚未验证的边界。功能范围见[实现方案](resource-library-implementation.md)，测试环境配置见[开发环境](../../contributing/setup.md)与[测试指南](../../contributing/testing.md)。记录日期：2026-09-26。

## 证据口径

- 下表的测试数量是各次定向执行的结果。套件包含公共回归，彼此有重叠，不能相加作为独立测试总数；当前分支新增用例后，同一命令的计数也可能变化。
- `desktopTest` 包含在 JVM 上执行的公共测试、Room 数据库测试与 Compose 合成输入测试。它不能证明 Windows 原生窗口、播放器或 Android 运行时已经通过验证。
- Desktop/Android 编译通过与 Android 安装成功分别记录，不代替运行时验收。
- Android 截图位于验收工作区的 `build/resource-android-ui/`，属于未提交的本地 artifact。下文路径均相对仓库根目录，不是可从 Git 仓库获取的永久证据链接。复现时应保存自己的截图与脱敏日志。

## 自动化验收矩阵

| 模块/场景 | 已记录结果 | 主要覆盖与限制 |
| --- | --- | --- |
| 活动候选与来源实例生命周期 | 108 项定向测试通过；application Desktop/Android 编译通过 | 关联增改删实时更新、来源增删不重跑既有查询、当前选择保持、自动选源与 fallback、条目会话复用、隐藏 SP 精确读取。包含公共 fetcher/Room 回归。 |
| 扫描协调与增量写入 | 34 项定向测试通过；application Desktop/Android 编译通过 | 同根任务合并、最多两个根并发、前台过期触发、取消等待与取消任务的区别、调度前取消、失败冷却、进程残留令牌恢复、完整扫描缺失判定。SQLite 触发器验证未变化资源及建议零重写；仍完整遍历并重新评估匹配。 |
| 下载与恢复 | 67 项定向测试通过 | 能力接口、恢复/重试与身份校验等下载管线回归；真实下载与离线副本证据见 Android 矩阵。 |
| BT 文件及关联管线 | 41 项定向测试通过 | 可控元数据与存储/界面回归；不代表真实 BT 网络、真实引擎跨集已经验收。 |
| 本地重新定位 | 31 项通过，其中 17 项新增测试、14 项回归 | 本机文件系统及关联/事务保护；单文件与目录的 Android 验收分别记录。 |
| 重新定位确认 UI | 5 项 Compose 测试通过 | 原位置/新位置、完整相对路径、大小差异、准备/提交状态、错误与取消。 |
| 关联文件行 UI | 2 项 Compose 测试通过 | BT 同名文件的完整路径、逐文件选择/跳过、同名不同来源的目录上下文。 |
| 资源入口与规则 UI、Android 安装 | 定向 UI 测试与安装成功 | 重新定位集成安装执行记录为 4 分 16 秒，菜单接线安装执行记录为 1 分 1 秒；耗时仅用于标识本次执行，不是性能基线。 |

## 可复现的自动化命令

在仓库根目录执行。Windows 使用 PowerShell，JDK 为带 JCEF 的 JBR 21，Android SDK 与本机路径通过未提交的 `local.properties` 配置。下面命令采用 PowerShell 数组避免跨行转义；同一工作区顺序执行，`--max-workers=2` 限制 Gradle 并行度。

### 活动候选、会话复用及 SP

```powershell
$testArgs = @(
    ':app:shared:app-data:desktopTest',
    '--tests', '*MediaFetcherTest*',
    '--tests', '*LiveMediaFetchSessionTest*',
    '--tests', '*MediaSourceInstancePoolTest*',
    '--tests', '*MediaAutoSelectorTest*',
    '--tests', '*MediaAutoSelectorWebTest*',
    '--tests', '*SubjectMediaFetchSessionsTest*',
    '--tests', '*SubjectCollectionRepositoryInvalidateTest*',
    ':app:shared:application:compileKotlinDesktop',
    ':app:shared:application:compileAndroidMain',
    '--max-workers=2'
)
./gradlew @testArgs
```

### 扫描、确认规则及数据库写入

```powershell
$testArgs = @(
    ':app:shared:app-data:desktopTest',
    '--tests', '*ResourceLibraryScanCoordinatorTest*',
    '--tests', '*ResourceLibraryIncrementalScanTest*',
    '--tests', '*ScanMatchingRulesTest*',
    '--tests', '*ResourceLibraryDaoTest*',
    ':app:shared:application:compileKotlinDesktop',
    ':app:shared:application:compileAndroidMain',
    '--max-workers=2'
)
./gradlew @testArgs
```

### 下载请求、恢复管线及访问刷新

```powershell
$testArgs = @(
    ':app:shared:app-data:desktopTest',
    '--tests', '*DownloadRequestSessionTest',
    '--tests', '*BatchDownloadPlannerTest',
    '--tests', '*HttpCacheRestorePipelineTest',
    '--max-workers=2'
)
./gradlew @testArgs
./gradlew :utils:http-downloader:jvmTest --tests '*HttpRequestRefreshTest' --max-workers=2
```

67 项执行记录来自 app-data 的 35/16/9 项及 http-downloader 的 7 项。HTTP 完成任务重启显示 Paused 100% 的真实运行问题单独列为待修复，不能用这组既有测试的通过结果覆盖该缺口。

### BT 待关联文件、浏览与持久化

```powershell
$testArgs = @(
    ':app:shared:desktopTest',
    '--tests', '*ResourceLibraryPendingFilesTest',
    '--tests', '*ResourceBrowserControllerTest',
    '--tests', '*ResourceLibraryEntryTest',
    ':app:shared:compileAndroidMain',
    '--max-workers=2'
)
./gradlew @testArgs

$testArgs = @(
    ':app:shared:app-data:desktopTest',
    '--tests', '*ResourceTorrentCatalogTest',
    '--tests', '*LibraryIgnoredFilesTest',
    '--tests', '*ScanMatchingRulesTest',
    '--max-workers=2'
)
./gradlew @testArgs
```

41 项记录包括 shared 的 5/9/2 项及 app-data 的 4/4/17 项。前者使用纯投影、fake 浏览器与 Compose 合成输入；后者使用实际 Room 测试数据库和构造的来源/元数据。缺少有效 BT 元数据时不能产生可选发布行，逐文件跳过按完整路径保存。此组证据不包含真实 BT 网络、原生 BT 播放或下载。
### 本地重新定位与关联保留

```powershell
$testArgs = @(
    ':app:shared:app-data:desktopTest',
    '--tests', '*LocalResourceRelocationTest',
    '--tests', '*SystemLocalRelocationTest',
    '--tests', '*LocalResourceRelocationControllerTest',
    '--tests', '*LocalRelativePathTest',
    '--tests', '*ResourceLibraryDaoTest',
    '--tests', '*LibraryResourcePlaybackTest',
    '--tests', '*LibraryIgnoredFilesTest',
    ':app:shared:compileKotlinDesktop',
    ':app:shared:compileAndroidMain',
    '--max-workers=2'
)
./gradlew @testArgs
```
### 确认界面与 Android 安装

```powershell
./gradlew :app:shared:desktopTest --tests '*ResourceRelocationDialogTest' --max-workers=2

$testArgs = @(
    ':app:shared:desktopTest',
    '--tests', '*ResourceAssociationFileRowTest',
    '--tests', '*ResourceLibraryEntryTest',
    '--tests', '*ResourceScanRulesScreenTest',
    ':app:android:installDefaultDebug',
    '-Pani.android.abis=x86_64',
    '--max-workers=2'
)
./gradlew @testArgs
```

安装命令需要已连接的 x86_64 Android 模拟器。Compose 测试使用合成点击/输入及已提交截图基线，不操作系统鼠标；复核时比较现有基线，不用更新基线来消除失败。

Gradle 报告位于各模块的 `build/reports/tests/desktopTest/`，JUnit XML 位于 `build/test-results/desktopTest/`。例如 app-data 的完整相对路径为 `app/shared/app-data/build/test-results/desktopTest/`。

## Android 真实运行验收矩阵

环境为 Android API 36、x86_64 模拟器，1080 × 2400，density 420。WebDAV 与 SMB 使用本机生成的视频 fixture 和只读测试服务；这不是对任意第三方服务配置的兼容性保证。

| 场景 | 结果 | 观察与 artifact |
| --- | --- | --- |
| SAF 本地目录导入 | 通过 | 经系统 document/tree 选择授权，关联本地 01/02，完成播放、切集和应用重启后的播放。 |
| 本地长视频进度恢复 | 通过 | 使用 600 秒的 03，在 2:01 退出；重启后继续播放，观察到 2:33。截图 `94-progress.png`、`101-resumed-progress.png`。这是退出位置及恢复后继续推进的证据，不表示恢复点精确为 2:33。 |
| WebDAV 01 浏览、关联与播放 | 通过 | 使用真实 WebDAV 测试服务，经应用来源入口浏览并关联，播放器读取视频。 |
| SMB 02 浏览、关联与播放 | 通过 | 使用真实 SMB 测试服务，经应用来源入口浏览并关联，播放器读取视频。 |
| 已确认目录规则处理新文件 | 通过 | 保存目录规则后新增 03，扫描自动关联第 3 集。截图 `84-auto-episode-three.png`。 |
| WebDAV/SMB 托管下载 | 通过 | WebDAV 01 显示 10.2 MB、SMB 02 显示 819.3 KB，下载完成。截图 `118-smb-downloaded.png`。显示大小按应用单位记录。 |
| 来源离线后播放 SMB 托管副本 | 通过 | 停止 WebDAV 与 SMB 两台 fixture 服务后，SMB 的已完成托管副本仍可播放。截图 `120-offline-smb-loaded.png`。 |
| SAF 单文件重新定位 | 通过 | 从 Downloads 选择 Replacement01 替代本地 01，预览提示大小差异；确认后仍绑定第 1 集，并播放替代文件的蓝色视频。截图 `129-relocation-preview.png`、`130-relocated-library.png`、`131-relocated-playback.png`。 |
| WebDAV 完成任务的重启状态 | 待修复/验证 | 重启后 WebDAV 01 从 Finished 显示为 Paused 100%，SMB 02 仍为 Finished。截图 `134-downloads-restored.png`；HTTP 完成任务恢复不能记为通过。 |
| WebDAV 托管副本的实际离线播放 | 未完成 | 首次下载完成已有证据；完成状态恢复存在上述问题，停止来源后的 WebDAV 副本播放尚未确认。 |
| Android 目录批量重新定位 | 未完成 | 不以单文件重新定位或 JVM 目录测试代替 Android 目录批量迁移验收。 |

### 真实运行复现步骤

1. 准备有可辨别画面的 01、02 和时长至少 600 秒的 03；准备大小不同、画面不同的 Replacement01。将本地文件放入可由 SAF 选择的目录，替代文件放入 Downloads。
2. 启动只读 WebDAV 和 SMB fixture 服务，分别暴露测试视频。在本次环境中，Android 通过 `10.0.2.2:18089` 访问宿主 WebDAV，通过 `10.0.2.2:1445`、共享 `videos` 访问宿主 SMB；服务仅绑定宿主回环地址。凭据使用自建 fixture 账号。
3. 安装应用，从资源入口添加本地目录，核对文件与条目/剧集后确认；分别播放第 1、2 集，切集并重启进程，检查来源和视频内容。
4. 播放长视频到约 2 分钟，退出并重启，确认回到相应进度并持续推进；同时记录退出前与恢复后的时间和画面。
5. 分别添加 WebDAV/SMB 来源，浏览、选择文件并关联到明确剧集，检查实际播放来自所选文件。
6. 在明确目录上确认规则，再向该目录加入 03；触发扫描，检查仅唯一规则命中的新增文件自动关联，已有手工决定保留。
7. 下载 WebDAV 与 SMB 视频，等待任务完成；停止 fixture 服务，再从托管副本入口播放，逐个来源记录结果。
8. 对本地 01 执行单文件重新定位，选择 Replacement01，先检查差异预览，再确认；核对绑定仍为第 1 集，并通过不同画面确认读取了替代文件。

这些步骤描述验收操作；fixture 服务、视频和截图未作为永久测试资产提交，复现环境需自行准备。目录重新定位应另行验证完整相对路径、整批确认与失败时原子保留，不能只测试同名文件末段。

## 尚未验证及适用边界

| 项目 | 已有证据 | 仍需完成 |
| --- | --- | --- |
| 真实 PikPak 账号 | SDK/适配器及可控接口测试 | 经明确授权的测试账号，对已有文件执行只读浏览、手动搜索、关联、播放与链接刷新；不能用 mock 结果代替账号验收。 |
| Windows 原生 UI 与播放器 | 真实 Windows 文件系统、SMB 随机读取检查，以及 Compose headless 测试 | 原生窗口、系统选择/拖放、播放器 seek/切集/重启、文件服务和下载副本播放。编译通过不覆盖这些场景。 |
| 真实 BT 网络与引擎跨集 | 可控元数据、完整路径选择、持久化与 UI 测试 | 真实种子元信息获取、取消、同名不同目录文件、跨集共享任务、引擎服务生命周期。 |
| WebDAV 完成状态恢复与副本离线播放 | 真实首次下载完成；重启显示 Paused 100% 的问题已复现 | 修复 HTTP 完成任务恢复，复查重启状态并在停止来源后实际播放该托管副本。 |
| Android 目录批量重新定位 | JVM/文件系统规则与确认 UI 测试 | 经 SAF 重新选择目录，核对完整相对路径，整批确认后逐集播放与重启复查。 |
| 更广的网络及设备条件 | 本机只读 fixture 服务、上述模拟器 | 未列出的服务实现、网络切换、真实设备及权限撤销场景需独立记录，不能由当前矩阵推定通过。 |
