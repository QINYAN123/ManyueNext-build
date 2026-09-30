# ManyueNext-Mihon-Fork 交付执行计划（交给 GPT-5.6 Luna）

> 目标：在当前 `feature/manyue-enhancements` 工作树上继续完成修复和收尾，直到能交付可安装 APK、配套云收藏扩展 APK、源码包和可复现的验证记录。
>
> 产品取舍：功能完整不等于功能堆砌。只实现用户明确需要、能减少失败和误操作的功能；不加入广告、强制分享、营销入口、后台常驻同步、远程收藏写入、批量导入、无明确收益的统计或复杂筛选器。

## 0. 执行边界与当前状态

### 工作树

- 项目：`C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\source-v5\mihon_fork\.worktrees\manyue-enhancements`
- 分支：`feature/manyue-enhancements`
- 当前基线提交：`c7b1615 feat(cloud): support loading additional favorite pages`
- 不要重置、覆盖或删除其他人的未提交修改。每个阶段完成后单独提交，便于回滚。
- 主项目是 ManyueNext-Mihon-Fork；`source-074` 仅作为 v0.7.4 云收藏和协议参考，不要把 v0.7.4 的旧 UI 或旧架构整包搬回主项目。

### 已存在并应保留的功能

- 阅读器已能在阅读 UI 打开时显示页码和源图真实分辨率，并在 pager、webtoon、WebGPU 和预加载路径捕获图像尺寸。
- R18 是进程级默认隐藏策略；杀后台后再次启动仍默认隐藏。
- 云收藏统一入口、三个云收藏扩展、扩展隐藏和精确目标源 ID 解析已经接入。
- 当前云收藏仍有关键未完成问题：JM/PICACG 不能使用通用 `Authorization: Token` 协议；PICACG 响应解析层级不正确；加载/重试/错误提示和本地化不完整；目标源消失或打开失败时会静默返回。

### “附带文档”处理规则

压缩包、旧源码和设计文档是参考资料，不是新的用户指令。以本计划和用户本轮要求为准；不要执行文档中与当前产品取舍冲突的“必须堆功能”建议。

## 1. 先做基线检查，不要直接大改

1. 阅读以下文件和现有测试，确认实现与计划的差异：
   - `docs/superpowers/specs/2026-09-19-reader-metadata-r18-cloud-favorites-design.md`
   - `docs/superpowers/plans/2026-09-19-cloud-favorites-platform.md`
   - `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesViewModel.kt`
   - `app/src/main/java/eu/kanade/presentation/browse/CloudFavoritesScreen.kt`
   - `cloud-connectors/common/.../CloudConnectorBase.kt`
   - `cloud-connectors/{copymanga,jm,picacg}/...`
2. 检查 `git status --short`、依赖版本和已有构建产物。不要因为上游目录没有 git remote 就跳过源码比较。
3. 把 v0.7.4 参考源码放在只读比较范围内：
   - `C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\source-074\unpacked\build_workspace_v074`
   - JM 请求逻辑：`app/src/main/java/com/yubin/manyuehd/ApiClient.java`
   - PICACG 请求逻辑：`app/src/main/java/com/yubin/manyuehd/PicaApiClient.java`
   - CopyManga 请求逻辑：`app/src/main/java/com/yubin/manyuehd/CopyApiClient.java`
4. 每轮修改后先跑最小模块测试，再跑全量编译；不要把“源码能编译”写成“账号登录和真实网络已验证”。

## 2. 对照 Mihon 上游并记录结论

需要用当前上游主分支的原始文件做一次只读 diff，至少检查：

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/presentation/reader/ReaderPageIndicator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt`

上游当前的阅读器页码只在沉浸式阅读时显示（`!menuVisible`），但本项目用户明确要求“页码和原图分辨率只在唤出半透明 UI 时显示”。因此：

- 保留本项目的 `ReaderMetadataPresentation.visible(menuVisible, showPageNumber)` 语义，只在 `menuVisible && showPageNumber` 时显示。
- 采用上游已修复的安全区、边缘设备和系统栏处理方式；不要因为追上游而反转用户指定的显示时机。
- 对 `ExtensionManager` 的受信扩展加载机制只做兼容，不改变签名信任流程。
- 云收藏扩展应继续从正常 source、迁移列表和 stub 中隐藏，但必须保留通过精确 source ID 打开扩展设置的能力。

在交付文档中记录：哪些地方跟随上游、哪些地方是用户定制差异、为什么有意不照搬。

## 3. P0：修复三个云收藏扩展的真实协议

当前 `CloudConnectorBase` 的通用 `Authorization: Token` 不能用于 JM 和 PICACG。必须先修协议，再优化 UI；否则 UI 再好也只是显示失败。

### 3.1 公共连接层

修改：

- `cloud-connectors/common/src/main/kotlin/app/manyuenext/cloud/common/CloudConnectorBase.kt`
- `EncryptedAccountStore.kt`
- `CloudLogRedactor.kt`

要求：

- 提供不强制附加通用认证头的原始请求函数，允许扩展完全接管 header、签名和响应 envelope。
- 统一超时、HTTP 状态映射、IO 异常映射和协程取消；`CancellationException` 必须原样抛出。
- 不把响应 body、Authorization、Cookie、JWT、AVS、API key、password 写入日志或 `CloudFavoritesException.userMessage`。
- 认证信息仍使用 Android Keystore AES-GCM 加密；坏密文应被视为未登录并允许用户重新配置，而不是导致整个扩展崩溃。
- preference 文案必须说清楚是“会话配置/令牌导入”还是“账号登录”，不能把粘贴 token 的输入框假装成登录页。
- `CloudLogRedactor` 增加并测试以下模式：`Bearer`、`Token`、`authorization`、`cookie`、`avs`、`jwt`、`password`、`secret`、`api-key`。

### 3.2 CopyManga

修改：

- `cloud-connectors/copymanga/.../CopyMangaCloudSource.kt`
- `CopyMangaApi.kt`

要求：

- 保留 `member/collect/comics` 只读接口，分页参数为 `limit=21`、`offset=(page-1)*21`、`ordering=-datetime_modifier`。
- 采用 v0.7.4 参考中的 signed app profile：`source=copyApp`、`platform=3`、固定兼容版本 `3.0.6`、`webp=1`、`x-auth-timestamp`、`x-auth-signature`、`umstring`、稳定的 device/pseudo id，以及 `Authorization: Token <token>`。
- API host 使用 v0.7.4 的可轮换候选并在网络失败时有限重试；不能无限循环或每次请求无条件重置 host。
- `accountState()` 只在 token 存在且一次最小收藏请求成功时返回 Ready；401/403 映射到 SessionExpired/LoginRequired。
- 解析 `data.results`/`data.list`，单条异常跳过；canonical URL、remoteId 和 title 必须非空。缩略图为空不能让整页失败。
- `hasNextPage` 同时支持 total 和 page-size 两种响应，不得在 total 恰好等于当前页上限时多加载一页。

### 3.3 JM

修改：

- `cloud-connectors/jm/.../JmCloudSource.kt`
- `JmApi.kt`

要求：

- 会话至少支持 `JWT` 和 `AVS` 两部分，建议以加密值 `jwt|avs` 存储；兼容只提供 JWT 的旧输入。
- 每次请求生成秒级 timestamp，并发送：
  - `Tokenparam=<timestamp>,2.0.21`
  - `Token=md5(<timestamp> + API_SECRET)`
  - `Authorization: Bearer <jwt>`
  - `Cookie: AVS=<avs>`（有 AVS 才发送）
  - 参考源码要求的 Accept/User-Agent。
- 收藏接口为 `favorite?page={page}&folder_id=0&o=mr`；不要把 JM 连接器注册成可阅读 source。
- 兼容 API envelope 和 v0.7.4 的加密 data；解密失败要映射为 Parsing，不要返回半解析数据。
- 收藏数据支持 `list`、`favorites`、`results`，ID 支持 `id`/`aid`/`album_id`，封面允许为空；canonical URL 统一为 `/album/{id}/`。
- `accountState()` 通过一次收藏请求验证会话；不能仅凭字符串非空就显示“已登录”。
- preference 入口文案改成“配置 JM 会话（JWT|AVS）”或提供真正的账号密码登录动作；如果扩展 Preference API 无法安全承载异步登录，必须使用前者，不能虚假显示“登录”。

### 3.4 PICACG

修改：

- `cloud-connectors/picacg/.../PicacgCloudSource.kt`
- `PicacgApi.kt`

要求：

- 使用 v0.7.4 的 API key/secret、版本、app-channel、app-platform、app-build-version、app-uuid、image-quality、User-Agent 等协议字段；app UUID 持久化，不能每次请求改变。
- 每个请求生成 `time` 和无连字符 `nonce`。签名原文为去掉开头 `/` 的 `path + query + time + nonce + method + API_KEY`，转小写后用 HmacSHA256(API_SECRET) 输出小写 hex。
- 登录会话使用 PICACG JWT，认证头为 `authorization: <jwt>`；不能发送 `Bearer` 或 `Token` 前缀。
- `Server-Time` 导致 401 时最多按时间偏移重试一次；网络失败、401、429、解析错误要分别映射。
- 收藏接口为 `users/favourite?page={page}&s=dd`。
- 修复当前解析 bug：真实响应通常是 `root.data.comics.docs`，还要兼容 `data.docs`/`data.list`；分页字段从 comics 或 data 读取。
- 修复缩略图：`thumb.fileServer` 若为相对路径，拼接 `/static/`；若已是绝对 URL 原样保留。
- 测试签名向量、嵌套响应、相对封面和空 docs。

### 3.5 账号/API 入口的产品规则

- 三个扩展的配置入口必须清楚显示当前状态：未配置、会话失效、已连接；不要只显示一个永远叫“Session token”的英文输入框。
- 清除/覆盖会话必须是可见、可恢复的设置操作；不提供远程收藏修改按钮。
- 如果实现真实账号密码登录，密码只用于一次登录请求，不落盘；只加密保存返回的会话。若本轮无法在扩展设置生命周期内安全实现，就交付“会话导入”并明确说明，不得伪造登录完成。

## 4. P0：云收藏 ViewModel 与 UI 可靠性

修改：

- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesViewModel.kt`
- `CloudFavoritesScreen.kt`
- `CloudFavoritesTab.kt`
- `CloudConnectorRegistry.kt`
- `CloudTargetResolver.kt`

### 状态模型

每个 provider 独立维护：`Loading`、`Loaded`、`Empty`、`LoginRequired`、`SessionExpired`、`Missing`、`Duplicate`、`RateLimited`、`Network`、`Parsing`、`Remote`。一个图源失败不能清空或阻塞另外两个图源。

### 加载和重试

- 首次加载三个 provider 可并发，但每个 provider 的状态单独提交；不要顺序等待 CopyManga 完成后 JM/PICACG 才出现。
- 刷新或重试时保留已显示的旧 items，显示小型 loading 状态；只有新页成功才替换/合并数据。
- 重试只能针对失败的 provider；不要点击一次刷新就把所有已加载列表清空。
- `loadMore` 必须使用最新 state 快照或互斥保护，防止快速连续点击重复页；取消时保留已有 items。
- 视图离开后停止网络请求，重新进入可安全恢复；任何取消都不能被转换成“网络失败”。
- 页码合并按 remoteId 去重；下一页检查页码递增和 hasNext，避免无限加载。

### 打开漫画

- 点击收藏项前再次用精确 target source ID 解析；目标源被卸载时发送明确的 Snackbar/Event（“未找到对应图源，请先安装或启用 …”），不得静默 return。
- 插入本地网络漫画前保证 source ID 是目标图源而非云连接器 ID。
- 插入异常要显示通用、可操作的提示；日志记录技术原因但经过脱敏。

### UI 交互

- 保留统一云收藏入口和三个独立区块/标签；不为“看起来高级”增加复杂导航。
- 每个区块显示图源名、连接状态、重试/设置按钮、空状态和加载更多。
- 已登录但空列表显示“暂无云收藏”；被 R18 过滤导致空列表时必须说明“内容已隐藏，可在本次运行中显示 R18”。
- 所有文字放进 `i18n/src/commonMain/moko-resources/{base,zh-rCN,zh-rTW}/strings.xml`，删除 `Refresh`、`登录或修复登录`、错误信息等硬编码英文/中文。
- AppBar 刷新动作使用本地化 `refresh` 字符串和无障碍描述。
- 列表项支持足够的点击区域、作者/源信息的层次、长标题省略；不要加入无收益的动画、排序、统计或远程同步。
- 失败信息给用户看安全的分类消息，不直接拼接异常 `message`；开发日志可记录脱敏后的 failure type。

## 5. P0：R18 筛选和状态保持

修改/检查：

- `app/src/main/java/eu/kanade/tachiyomi/ui/adult/AdultContentClassifier.kt`
- `AdultContentPolicy.kt`
- `AdultContentSessionState.kt`
- library/source browse/cloud UI 和对应 strings/tests

要求：

- 进程启动默认隐藏；不写成永久偏好，杀后台后重新隐藏。
- 规范化标签匹配至少覆盖：`R18`、`R-18`、`18+`、`成人`、`成年`、`NSFW`、`Hentai`；匹配标签/源警告，不要仅凭标题关键词误伤普通内容。
- 扩展声明 warning 优先；已知 CopyManga/JM/PICACG 的保守 source policy 只能作为兜底，不能覆盖明确的安全标签。
- 图源列表、书架、云收藏使用同一个 session state 和同一套 classifier；不要出现“书架隐藏、云收藏却显示”的不一致。
- UI 放一个明确的“显示 R18（仅本次运行）”开关/筛选项，并在启用时显示 active chip；关闭后立刻重新过滤已加载列表，不必强制网络刷新。
- 默认隐藏后的空状态告诉用户原因和操作路径；不加入 PIN、密码、自动定时显示等鸡肋保护层。

## 6. P0：阅读器真实分辨率显示的最终审查

修改/检查：

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/presentation/reader/ReaderPageIndicator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata/*`
- pager/webtoon/WebGPU 读取图片的路径

验收语义：

- 菜单/UI 唤出时显示：`第 x / y 页 · 原图 W × H`；菜单隐藏时完全不显示任何页码/分辨率文字，保持纯净阅读。
- 仍尊重现有 `showPageNumber` 开关；开关关闭时不显示。
- 图片尚未解码显示“原图尺寸读取中”，明确失败显示“原图尺寸不可用”，不显示 `0x0` 或假尺寸。
- 采集的是源图真实尺寸，必须发生在 Manyue 变换、裁剪、缩放之前；WebGPU 首帧和预加载也要写入同一 metadata 状态。
- 使用半透明 surface、圆角和系统安全区 padding；不能遮挡底部手势区域，不能制造大块不透明黑条。
- 对照上游 `ReaderPageIndicator` 的边缘设备修复，但保持本项目“仅打开 UI 时显示”的产品语义。

## 7. 系统性 bug 扫描清单

逐项检查并用测试或代码证据关闭：

1. 扩展加载/卸载竞态：扩展消失时 UI 不崩溃，重复 provider 变成 Duplicate。
2. 目标图源卸载：不回退到同名或同包其他 source，不把漫画插到云连接器 ID。
3. 认证过期：401/403 不显示空列表，保留旧 items 并引导设置/重试。
4. 429/网络断开/解析错误：分类显示、可重试、互不污染。
5. malformed item：跳过单条坏记录；malformed envelope：只失败当前 provider。
6. 分页：page 从 1 开始、重复点击不重复请求、最后一页不死循环、合并去重。
7. 协程：IO 请求在 IO dispatcher；取消原样传播；屏幕离开不继续更新已销毁 UI。
8. 安全：任何异常、日志、Snackbar 都不得泄漏 token、JWT、AVS、Cookie、API secret、密码或完整 URL query 中的敏感值。
9. UI：旋转/返回/重新进入后状态可恢复；loading、empty、error、retry、settings 都可操作；长标题和大字体不溢出。
10. 阅读器：页码越界、总页数未知、元数据切换旧页时不能短暂显示错误尺寸。

## 8. 测试矩阵（必须执行）

### 纯单元测试

- `CloudModelsTest`：非法 page、空 URL/title、异常 userMessage 不泄漏 cause。
- Registry/target resolver：available、duplicate、missing、禁止按名称 fallback。
- 成人内容：所有标准 tag、大小写/空白/连字符归一化、非标签标题不误判、进程默认隐藏。
- Reader metadata：loading/available/unavailable、选页切换不串数据、显示条件。
- Copy/JM/Pica parser：真实 v0.7.4 fixture、空/坏 envelope、单条坏 item、分页边界。
- 签名测试：Copy HMAC、JM MD5、PICACG HMAC 至少各一组固定向量。
- `CloudLogRedactor`：Bearer/Token/Cookie/AVS/JWT/API key/password 全部脱敏。

### Gradle 验证命令

在 PowerShell 先设置（每个新终端都要设置）：

```powershell
$env:TEMP='C:\jt'
$env:TMP='C:\jt'
$env:JAVA_HOME='C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\toolchains\jdk21\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\toolchains\android-sdk'
```

依次运行：

```powershell
.\gradlew.bat --no-daemon :source-api:testDebugUnitTest :source-api:assembleDebug
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.reader.metadata.*' --tests 'eu.kanade.tachiyomi.ui.adult.*' --tests 'eu.kanade.tachiyomi.ui.browse.cloud.*'
.\gradlew.bat --no-daemon :app:compileDebugKotlin :app:assembleDebug
.\gradlew.bat --no-daemon :cloud-connectors:common:testDebugUnitTest :cloud-connectors:copymanga:testDebugUnitTest :cloud-connectors:jm:testDebugUnitTest :cloud-connectors:picacg:testDebugUnitTest
.\gradlew.bat --no-daemon :cloud-connectors:copymanga:assembleDebug :cloud-connectors:jm:assembleDebug :cloud-connectors:picacg:assembleDebug
```

如果某个 connector module 当前没有 test task，先补 test source set/dependency，再执行等价的 parser test；不能把“没有测试任务”当成通过。

### 真实设备/网络边界

- 至少做一次安装后冷启动、杀后台再启动、无网络打开云收藏、单 provider 认证失效、目标源未安装、Reader 菜单开关和旋转检查。
- 若没有可用账号或网络，报告为“静态/编译/fixture 已验证，真实账号未验证”，不要伪称登录成功。
- 三个扩展分别安装并信任后，确认主程序 source 列表不会出现云连接器，但云收藏入口能发现它们。

## 9. 打包和交付

全部测试通过后：

1. 生成并检查：
   - `ManyueNext-v5-debug-universal.apk`
   - `ManyueNext-v5-debug-arm64-v8a.apk`
   - `ManyueNext-cloud-copymanga-debug.apk`
   - `ManyueNext-cloud-jm-debug.apk`
   - `ManyueNext-cloud-picacg-debug.apk`
   - `ManyueNext-Mihon-Fork-v5-final-source.zip`
2. zip 解压检查：源码、计划、测试和构建脚本在内；不得包含 token、Cookie、登录响应、构建缓存或临时目录。
3. 对 APK 和 zip 计算 SHA-256，写入 `MANYUE-DELIVERY-VERIFICATION.md`。
4. 交付报告必须分开写：
   - 已修复并有测试证据的 bug；
   - 已完成但只做静态/fixture 验证的功能；
   - 真实账号/网络未验证的边界；
   - 与 Mihon 上游一致的部分和有意保留的 Manyue 用户定制差异；
   - 明确没有加入的鸡肋功能及原因。
5. 最后一次 `git status --short` 必须只剩预期构建产物（若构建产物不入 git 则应为空），并记录最终 commit 列表。

## 10. 明确不做的功能

- 云端收藏夹写入、取消收藏、批量导入、后台定时同步。
- 为三种图源再造一套复杂的站内搜索、推荐、历史、阅读器；这些应继续交给已安装的 Keiyoushi/TheNano 源。
- 标题关键词式 R18 猜测、永久保存“显示 R18”、启动广告、强制分享、积分、统计报表。
- 无法证明能减少失败的复杂筛选器、排序器、动画和设置项。

交付标准不是“代码更多”，而是：核心协议真实正确、失败状态可理解且可恢复、阅读器信息不打扰、R18 默认行为稳定、扩展生命周期安全、测试和构建结果可复现。
