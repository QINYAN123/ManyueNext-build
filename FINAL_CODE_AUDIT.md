# Manyue 最终代码审计

审计日期：2026-09-17  
审计范围：Manyue 阅读器增强、ARM64 native AI 链路、Pager/Webtoon 调度、折叠屏宽度、内存与临时文件、最终构建产物。  
明确未改动：数据库、下载体系、扩展安装体系、漫画源体系、MangaDex 扩展逻辑。

## 结论分级

- **已由实际命令验证**：`compileDebugKotlin`、完整 JVM 单元测试、`assembleDebug`、APK v2 签名、APK 内四个 AI 文件及 SHA-256、ELF/PIE/ARM64/动态依赖、`extractNativeLibs=true`。
- **已由生产代码审计和单元测试验证**：mode/generation/identity/token 防旧结果、优先级规则、显式创建 next 3、折叠宽度算法、lower median、像素阈值与回退。
- **需要用户真机操作**：Android 内实际 `ProcessBuilder.start()`、native exit code、真实翻页抢占顺序、荣耀折叠态切换、长期内存曲线和视觉效果。验收步骤见 `DEVICE_TEST_CHECKLIST.md`。

本机 `adb devices -l` 返回空设备列表，因此本文没有把任何真机项目写成“已通过”。

## UI 与生产入口

Manyue 沿用现有 Reader 设置页，没有新建第二套 UI。

| 功能 | UI 入口 | 生产调用链 |
|---|---|---|
| 原图 / OFF | `GeneralSettingsPage.kt` Manyue 增强区 | `ManyueRuntimeState.updateMode(OFF)`；Holder 的 OFF 路径不读取整张 `ByteArray`，直接把 Mihon 原始 `BufferedSource` 交给 Reader |
| 经典增强 | 同一设置区“经典增强” | `PagerPageHolder` / `WebtoonPageHolder` → `ManyueReaderHook.applyClassic()` → `ManyueClassicEnhancer.enhance()` → `ReaderPageImageView.setImage()` |
| AI×2 | 同一设置区“AI×2” | Holder → `ManyuePageBridge.tryStartAi()` → `ManyueAiRequestFactory.enqueue()` → `ManyueAiUpscaler` 单 worker → `ManyueAiRuntime.upscale()` → cache → Bridge 替换 Reader 图片 |
| AI×2 + 经典增强 | 同一设置区“AI×2 + 经典增强” | 先完成同一 AI cache 链，再由 Bridge 对 AI bitmap 调用 `ManyueClassicEnhancer`；不再对原图先做一次无意义的大 bitmap 复制 |
| 经典增强强度 | 同一设置区 Slider | `ReaderActivity.onManyueClassicStrengthChanged()` 更新 generation、取消旧任务并刷新 Viewer |
| 折叠屏 AUTO / FULL / MANUAL | 同一设置区三个 Chip；MANUAL 显示 1360–2880px Slider | `ReaderActivity.onManyueFoldableWidthChanged()` → Viewer `reapplyManyueWidth()` → `ManyueFoldableController.applyToTree/applyToView()` |

设置变更会刷新现有 Pager/Webtoon Holder；不是只写 Preference 等待下次启动。

## AI native 启动与 APK 访问

### 打包

`app/build.gradle.kts` 对 `jniLibs` 开启 `useLegacyPackaging=true`。最终 Manifest 确认 `android:extractNativeLibs=true`，所以安装后 `libmanyue_realesr.so` 与 `libncnn.so` 会进入 `applicationInfo.nativeLibraryDir`，而不是只能留在 APK zip 中。两个文件加入 `keepDebugSymbols`，避免 Android Gradle Plugin 改写 PIE executable。

最终 universal APK 中：

| 文件 | APK 路径 | 字节 | SHA-256 |
|---|---|---:|---|
| `libmanyue_realesr.so` | `lib/arm64-v8a/` | 7,835,384 | `D74E2FF5366A3B548118D78D72A4E8E197C764DFDA4222A1718C10F50CDECE2B` |
| `libncnn.so` | `lib/arm64-v8a/` | 11,062,000 | `87D150E735157B09AA20F26F5E57F72468C548E7CE98CE407EC50EE7E14A52DD` |
| `x2.bin` | `assets/ai/models-Real-ESRGANv3-anime/` | 1,247,368 | `548A36F9C3F4AB8DA56CD3B13BADF23968BEE207B396DAD14D04B830E5F2AB2D` |
| `x2.param` | 同上 | 3,173 | `B88FF4F00EBF019A7FDAC17FDD45A7FD3665D37509EFC5BAF2E4DA2E24420A04` |

ELF 审计：ELF64、AArch64、ET_DYN/PIE、入口点 `0x229800`、解释器 `/system/bin/linker64`、`DT_NEEDED: libncnn.so`、`FLAGS_1: PIE`。它被当作可执行文件启动，不走 JNI。

### 运行链

`ManyueAiRuntime.upscale()` 在启动前逐项验证：

1. 输入文件存在且非空；
2. `libmanyue_realesr.so` 与 `libncnn.so` 存在且非空；
3. executable 具备执行权限，必要时尝试 `setExecutable`，仍不可执行则失败；
4. `x2.bin`、`x2.param` 已用 `.pending` 临时文件原子释放到 app files 目录；
5. 输出文件预删除，stdout/stderr 重定向到文件，避免未消费管道把 native 进程卡死；
6. `ProcessBuilder` 工作目录为 native library dir，并设置 `LD_LIBRARY_PATH=nativeLibraryDir`；
7. 每 250ms 检查取消和 240 秒 timeout；取消/超时执行 `destroy()`/`destroyForcibly()` 并回收进程；
8. 校验 exit code、输出存在且非空；失败统一保留原图。

真机日志点为：`Manyue native start` → `Manyue native process started` → `Manyue native exit code=0 outputBytes=...`。

## AI 完成后的二次校验

`ManyuePageBridge` 在请求开始时捕获：

- token；
- `mangaId/chapterId/pageIndex` identity；
- enhancement mode；
- session generation；
- combined classic strength。

回调收到 READY 后先调用 `ManyueAiSafetyPolicy.isCompletionCurrent()`；cache 解码/经典增强/压缩全部完成后，在真正调用 `ReaderPageImageView.setImage()` 前再次执行同样校验。任一 token、identity、mode 或 generation 不一致都丢弃结果。

因此快速翻页、切 OFF、切章节、Viewer destroy、调整经典强度后，旧结果不能覆盖新页。回调 listener 在通知时移除；Holder recycle/detach 也会取消 Bridge。

## current 与 next 3 的真实创建

- 当前可见 Holder 通过 `ManyuePageBridge` 创建 priority `100` 请求。
- `PagerViewer.onReaderPageSelected()` 和 `WebtoonViewer.onPageSelected()` 都调用各自的 `ManyueReaderPrefetcher`。
- Prefetcher 根据当前 chapter 的实际 `ReaderPage.index` 调用 `prefetchPlan()`，显式加载并创建 `+1=30`、`+2=20`、`+3=10`，不是只给已存在的 Holder 改优先级。
- `ManyuePrefetchManager` 用 `(chapterId,pageIndex)` 跟踪 token；页面移出窗口、章节改变或新 token 替代旧 token 时取消旧请求。
- `ManyueAiUpscaler` 使用单 worker `PriorityBlockingQueue`，高 priority 位于队首。正在运行的任务持有 `Process` 引用，取消会杀进程；队列任务直接移除。
- 真机可通过 `Manyue AI queued ... priority=...` 与 `Manyue prefetch create ... priority=...` 观察实际创建顺序。

## foldable width 最终作用位置

- `ReaderActivity.onCreate()` 立即用 `resources.configuration` 初始化 `ManyueFoldableController`，已修复 `lastConfig == null` 导致首次在展开内屏打开 Reader 时错误返回 false 的问题。
- `onConfigurationChanged()` 更新快照并对已存在 Viewer 立即重算。
- Pager 最终作用于 `PagerPageHolder`（它本身是 `ReaderPageImageView`）。
- Webtoon 最终作用于每个 Holder 的 `ReaderPageImageView frame`。
- `applyToTree()` 可重算已创建 View；折回外屏、普通手机或 FULL 会把宽度恢复为 `MATCH_PARENT`。
- Webtoon 重排前记录 first visible position 与 top offset，在下一次 layout 后用 `scrollToPositionWithOffset()` 恢复，尽量保持相对阅读位置。
- AUTO 不再引用 AI 的 2344px 目标上限；偶数样本 width/ratio median 都取 lower median；MANUAL 会限制在屏幕宽度以内。

## Viewer / chapter / mode change 清理

- mode 或经典强度改变：generation 加一，`ManyuePrefetchManager.reset()` 取消所有 token，Viewer 重新绑定页面。
- chapter 改变：Viewer 的 `setChapters`/`setChaptersInternal` 重置 prefetcher、generation、token 和 Golden Reference samples。
- Viewer destroy：销毁 prefetch coroutine scope、取消请求、杀运行中的 native 进程并增加 generation。
- Holder detach/recycle：取消自身 Bridge 和 listener。
- worker finally：删除 `manyue_in_*` 输入和整个 `manyue_work_<token>`（含 native log、输出、segments）。
- cache bundle 仍采用 `.pending` 目录后原子 rename；cache 超过 768MiB 时回收至 640MiB。

## 内存与长图

- OFF 在 Holder 中只做 header bounds 流式探测，不复制完整原图。
- native 启动前按预测 2x 尺寸检查；native 输出及最终显示 bitmap 上限为 12,000,000 pixels，超限保留原图。
- 经典增强上限为 6,000,000 pixels；AI+经典超限时保留原图。
- cache 分段加载先只读 bounds，验证总像素后一次只 decode 一个 part、绘制后立即 recycle；不再同时 decode 全部分段。
- native raw、scaled、segment、Bridge AI/classic bitmap 均在 finally 中释放；临时目录随请求结束删除。

## 最终构建结果

最后一次代码变更后实际执行：

- `./gradlew :app:compileDebugKotlin`：**BUILD SUCCESSFUL**，2026-09-17。
- `./gradlew :app:testDebugUnitTest`：**BUILD SUCCESSFUL**；45 个 Manyue 测试 + 6 个 Migrator 测试，51/51 PASSED。
- `./gradlew :app:assembleDebug`：**BUILD SUCCESSFUL**。
- universal debug APK：package `app.mihon.dev`，versionName `0.20.4-1`，minSdk 26，包含 `arm64-v8a/armeabi-v7a/x86/x86_64`；AI 只在 arm64-v8a 启用。
- APK 签名：Android Debug，APK Signature Scheme v2 验证通过。

完整命令输出和包体静态核验日志位于交付的 `build-test-logs` 目录。

