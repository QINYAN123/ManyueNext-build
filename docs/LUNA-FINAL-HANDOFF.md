# Luna 最终执行交接：直接完成并交付

本文件是最新交接，优先于 MANYUE-LUNA-EXECUTION-PLAN.md 的冲突建议。用户已要求暂停 GPT-6，接下来由 Luna 全程执行、自检、修复、打包，不需要 GPT-6 再审批。不要新开代理或重新进行全面规划。遇到真实账号或设备缺失时明确标注验证边界，继续完成可独立完成的工作。

## 当前位置与暂停状态

- 工作目录：`C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\source-v5\mihon_fork\.worktrees\manyue-enhancements`
- 分支：`feature/manyue-enhancements`；HEAD：`4941376`。
- 用户最新要求是暂停，本文档本身不代表恢复执行；用户切换 Luna 并要求执行后再开始。
- 两个旧代理已分别暂停；当前检查无 Java 构建进程。
- 工作树有大量未提交修改，全部保留并接续，不要 reset、重解压或重建工作树。
- 主项目仍是 ManyueNext-Mihon-Fork；v0.7.4 只作协议参考。

## 已完成且不应重做

- `4941376`：把阅读器真实尺寸信息从底栏下方移动到 ReaderAppBars 内、控制按钮上方，解决 UI 打开时信息被底栏盖住的问题。
- 本轮已通过 `:app:compileDebugKotlin` 和 `:app:testDebugUnitTest --tests eu.kanade.tachiyomi.ui.reader.metadata.* --tests eu.kanade.tachiyomi.ui.adult.*`。
- 验证日志：`C:\jt\manyue-host-review.log`，末尾 BUILD SUCCESSFUL in 2m 1s。
- 上述验证早于最后一轮连接器编辑，不代表最新连接器已经编译通过。
- 当前 `outputs` 中 APK/源码包是旧产物，不得原样作为本轮新交付。
- `adb devices -l` 当前无设备；不能声称实机安装、交互、真实账号已验证。

## 尚未提交的进度

1. 三个连接器均正在补签名、解析、用户名密码登录；公共网络层刚改为可取消的 enqueue 回调，仍需编译及异常检查。
2. 新增 source-api `CloudPasswordLoginSource`：`suspend fun login(username: String, password: CharArray): CloudAccountState`。契约要求 host 管理生命周期、密码不持久化、连接器 finally 清空数组。
3. 主程序 CloudFavoritesViewModel/Screen/Tab 和三种 strings 文件有上一轮未完成的交互改动。
4. 四个连接器模块已新增测试及依赖，但不要假定已运行成功。

## 最短执行顺序

### 第一批：完成连接器与账号入口

先读现有差异，只修仍存在的错误。参考资料已在本机，无需重复下载：

- `C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\source-074\unpacked\build_workspace_v074\app\src\main\java\com\yubin\manyuehd`：ApiClient.java、PicaApiClient.java、CopyApiClient.java。
- `C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\extension-refs`：keiyoushi、thenano、yuzono-main、yuzono-cursed。thenano/index.json 可核对源 ID/包名；阅读源路径格式需与实际扩展代码对照。

必须修/核实：

- 公共 enqueue 的 onResponse 中 `body.string()` 可能抛 IOException，必须捕获并恢复 continuation 异常，否则回调线程异常、调用方永远等待。任何路径关闭 response；取消调用 call.cancel；不要吞 CancellationException。
- 令牌 EditTextPreference 不持久化明文、不预填加密串，使用密码掩码；清除会话给可见反馈。
- Copy/JM/Pica 的 HTTP 和业务错误不能被缺少 list 的默认空数组伪装成“没有收藏”；检查 root code 与 malformed envelope。
- Copy 收藏列表可能包裹在 item.comic 中；核对 v0.7.4 parsePage 的实际结构。登录解析不要将已分类 Authentication 包装成 Parsing。使用已核实 host，禁止凭猜测向新域名发送密码/token。有限 failover、记住成功 host，避免每次从坏线路试起。
- JM 必须使用 API 线路而不是网站 jmcomic2.cc；核实动态线路、timestamp/Tokenparam、JWT/AVS、AES 解密，以及阅读扩展 canonical URL 格式。
- PICACG 支持 data.comics.docs；封面应组合 thumb.fileServer 与 thumb.path，而不是把 fileServer 当图片。必须核对阅读扩展是否期待相对路径/ID，不能假定绝对 API URL 可阅读。
- accountState 可以仅检查本地会话，然后一次 getCloudFavorites 验证过期；旧计划要求的“accountState先请求第一页，host再请求一次”应删除，避免重复请求。
- 固定向量测试签名、真实结构 fixture 测试解析和分页、HTTP/业务错误、取消和脱敏；同一编译错误不要盲试超过两次，先查具体 API/类型。

账号体验必须落地：在主程序云收藏中调用 CloudPasswordLoginSource 的登录表单，使用生命周期内协程。输入账号、密码，有提交中、错误、取消、成功后刷新单源；密码使用内存状态，不用 rememberSaveable，不写日志、磁盘。密码 CharArray 在 host finally 再次清空。高级令牌输入仅作备用，不应把它冒充正常账号登录。

### 第二批：修云收藏并发、状态与可见交互

以下是 GPT-6 已实际读代码确认的问题，请逐个消除：

1. refresh 使用 async 后 awaitAll，再发布三个结果，最慢源仍会阻塞全部显示。改成每个请求完成立即更新自己的 provider。
2. loadProvider catch(Exception) 吞掉 CancellationException，必须单独原样抛出。
3. refresh/retry/loadMore 没有 generation 或 job 管理，旧请求可能覆盖刷新、卸载和新会话结果。为每个 provider 管理任务/代次；切换连接器或会话立即使旧响应失效。
4. loadMore 在 launch 前读取旧快照、进入协程后才置 loading，连续点击会重复请求；原子地占用加载状态或以单源 Job/Mutex 防重入。
5. 刷新捕获 showAdult 旧值，请求完成后可能重新暴露刚被隐藏内容。保留原始 items，发布时从最新 session flag 派生可见 items；开关变化无需重新请求。
6. 失败时虽然保存 items，UI 只在 Loaded 分支渲染，用户仍看不到旧列表。把内容列表与 loading/error 状态分离，重试不抹掉成功结果；保留正确分页游标，追加失败重试当前页。
7. 一整源的全部漫画放在单个 LazyColumn item 内 forEach，长列表会一次组合所有项。改为真正懒加载，key 使用 provider + remoteId。
8. 全页被 R18 隐藏时仍须允许下一页；区别“无收藏”和“本次加载内容被筛选隐藏”，显示明确开关状态。
9. open() 网络漫画插入异常未捕获；目标源缺失需要可操作提示，禁止同名 fallback 或把 connector ID 写为阅读源 ID。
10. Screen/Tab 剩余硬编码文案全部归入 base/zh-rCN/zh-rTW；优先复用已有字符串。使用 TabContent 已提供的 SnackbarHostState。
11. tab 内容生命周期控制网络请求；注意 cloudFavoritesTab() 在 BrowseTab 列表构造时就创建 ViewModel，不能仅靠 ViewModel onCleared 判断是否可见。返回登录/设置后自动刷新相应状态，退出时取消正在执行的请求。

R18 与阅读器：保持当前进程启动默认隐藏和已通过的元数据功能。不要把已知成人源因泛化 SAFE 标记就改成可见；元数据缺失无法保证精准识别所有混合源作品，应明确边界。检查 source browse PagingData 在切换筛选时是否重复消费未缓存数据，必要时先 cachedIn 再派生过滤，并加针对性回归。保留页码设置开关，只在唤出 UI 时显示原图尺寸。

### 第三批：自检、构建与真实交付

按改动范围运行测试，修复失败后再打包。当前工具链（PowerShell）：

```powershell
$env:TEMP='C:\jt'
$env:TMP='C:\jt'
$env:JAVA_HOME='C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\toolchains\jdk21\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\toolchains\android-sdk'
.\gradlew.bat --no-daemon :source-api:testDebugUnitTest :cloud-connectors:common:testDebugUnitTest :cloud-connectors:copymanga:testDebugUnitTest :cloud-connectors:jm:testDebugUnitTest :cloud-connectors:picacg:testDebugUnitTest
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.reader.metadata.*' --tests 'eu.kanade.tachiyomi.ui.adult.*' --tests 'eu.kanade.tachiyomi.ui.browse.cloud.*'
.\gradlew.bat --no-daemon :app:assembleDebug :cloud-connectors:copymanga:assembleDebug :cloud-connectors:jm:assembleDebug :cloud-connectors:picacg:assembleDebug
```

先补 cloud host 必要回归测试：取消、旧结果丢弃、单源失败隔离、R18 请求中切换、分页去重/重试、缺失目标源禁止插入。选纯状态控制器或可注入依赖，避免为测试做大架构重写。

- 检查 git diff --check，提交已通过验证的分阶段修改，不把凭据或日志写入源码包。
- 构建后确认主 APK 和三扩展的实际 manifest、入口类、ABI、版本、签名兼容；没有实机不能把包检查称为安装通过。
- 更新 `C:\Users\Administrator\Documents\Codex\2026-09-19\ba\outputs` 的 universal/arm64 主 APK、三个 connector APK、最终源码 zip、SHA256 和验证说明。仅复制本轮成功构建产物。
- 源码包必须覆盖最新修改及 source-api 新接口；排除 .gradle/build 缓存、账户信息、临时文件。解压或列目录检查可重建所需文件齐全。
- 发布说明写清：修复内容、测试命令/结果、未验证真实账号和设备、安装和信任扩展步骤。当前阶段可交付测试版；没有真实网络/账号证据不能标记线上登录已验收。

## 省额度和终止条件

- 使用当前文件与差异，不重复检索所有源码；日志落文件，只读取错误摘要。
- 一个 Luna 全程顺序执行；不创建多个模型，不让 GPT-6 等待轮询或逐次审批。
- 达到“正常登录入口、协议与解析有证据、关键交互可恢复、测试通过、APK/源码包可交付”即收尾。
- 不加远程收藏写入、批量导入、常驻同步、推荐算法、复杂报表、装饰性功能。若外部条件阻塞，完成其余工作并明确阻塞事实，不能用空数据或硬编码成功掩盖。
