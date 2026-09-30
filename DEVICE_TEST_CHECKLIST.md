# Manyue 真机验收清单

本清单用于 ARM64 Android 真机，重点设备为荣耀折叠屏。代码审计机没有连接 ADB 设备，以下项目必须由用户操作后才能标记通过。

## 0. 安装与采集准备

- [ ] 设备执行 `adb shell getprop ro.product.cpu.abi`，结果为 `arm64-v8a`。
- [ ] 安装：`adb install -r ManyueNext-Mihon-Fork-v5-universal-debug.apk`。
- [ ] 确认安装包：`adb shell dumpsys package app.mihon.dev | findstr versionName`。
- [ ] 清日志：`adb logcat -c`。
- [ ] 开一个日志窗口：`adb logcat | findstr /i "Manyue"`。
- [ ] 需要首次 native 实跑时选择一张尚未产生 AI cache 的页面；cache 命中不会再次启动 native。

## 1. UI 与模式入口

进入 Reader → 设置 → General → Manyue 增强：

- [ ] 能看到并点击“原图 / OFF”。
- [ ] 能看到并点击“经典增强”。
- [ ] 能看到并点击“AI 超分”。
- [ ] 能看到并点击“AI 超分 + 经典增强”。
- [ ] 能选择 AI 输出宽度 AUTO / 原生 2× / MANUAL；MANUAL 显示 1360–2880px Slider。
- [ ] 页面左上状态能区分排队、AI、经典、组合、跳过与失败，不再只显示“原图”。
- [ ] “经典增强强度” Slider 可调，当前页会重新绑定并体现变化。
- [ ] 折叠屏宽度有 AUTO / FULL / MANUAL。
- [ ] MANUAL 被选中后显示 1360–2880px 宽度 Slider，调节会立即重排当前 Reader。
- [ ] 普通手机或外屏选择 AUTO/MANUAL 不会破坏 Mihon 原始全宽布局。

## 2. native AI 完整链

在一张普通非动画、非超长页面选择 AI 超分：

- [ ] 日志出现 `Manyue AI queued ... priority=100`。
- [ ] 日志出现 `Manyue native start ... LD_LIBRARY_PATH=...`。
- [ ] 日志出现 `Manyue native process started`。
- [ ] 日志出现 `Manyue native exit code=0 outputBytes=...`。
- [ ] 日志最后出现 `Manyue reader replace chapter=... page=... token=...`。
- [ ] 页面先显示原图，AI 成功后只替换同一页，没有闪到别页。
- [ ] 选择 AI 超分 + 经典增强，完成图相对 AI 超分有经典增强效果。
- [ ] 用 1400×1500 静态测试图选择“原生 2× + 组合”，得到约 840 万像素结果时，应保留 AI 图并提示经典后处理超过限制。
- [ ] 模型已释放：`adb shell run-as app.mihon.dev ls -l files/ai_runtime_anime_dynamic_v3`，应看到非空 `x2.bin` 和 `x2.param`。
- [ ] 若 native 失败，Reader 保持原图且 App 不崩溃；日志包含 exit code/timeout/native log tail。

## 3. 调度、抢占与 stale-result

选择 AI 模式，从同一章节中部开始：

- [ ] 日志真实出现 current `priority=100`。
- [ ] 日志真实出现 +1 `priority=30`、+2 `priority=20`、+3 `priority=10`。
- [ ] 快速连续向前翻 8–10 页，新的当前页优先处理；旧低优先级任务出现 `Manyue AI cancelled` 或不再替换页面。
- [ ] 停在新页，确认旧页结果不会覆盖新页。
- [ ] AI 处理中切到 OFF，切换后不再出现旧 token 的 `Manyue reader replace`。
- [ ] AI 处理中改变经典强度，旧 generation 结果不替换新设置页面。
- [ ] AI 处理中切章节，旧章节 token 不替换新章节。
- [ ] AI 处理中退出 Reader，App 不崩溃，后台不继续大量占用 CPU。

## 4. 折叠屏

- [ ] App 已在内屏展开状态时，从书架直接首次打开 Reader；AUTO 首次即生效，不需要先折一次再展开。
- [ ] 内屏展开 → 合拢外屏，宽度立即恢复 Mihon 全宽。
- [ ] 外屏 → 展开内屏，宽度立即按 AUTO/MANUAL 重算。
- [ ] FULL 始终使用屏幕全宽。
- [ ] MANUAL 在内屏立即改变宽度，且不超过当前屏幕宽度。
- [ ] AUTO 的正常比例页面可以得到大于 2344px 的目标（屏幕和 Golden Reference 足够大时），证明没有复用 AI 2344px cap。
- [ ] Pager 横/竖翻页均无异常页宽或无法翻页。
- [ ] Webtoon 在展开/合拢重排后仍停在当前页附近，不跳回章节页首。
- [ ] 普通直板手机完整走一章，布局与原 Mihon 一致。

## 5. 长图、内存和临时文件

- [ ] 在 Webtoon 打开普通长图，滚动和 AI 替换无崩溃。
- [ ] 打开极长图/4K 图；若超过阈值，应保留原图，不发生 OOM。
- [ ] OFF 模式连续阅读 20 页，内存相对 Mihon 原路径无明显额外整图复制峰值。
- [ ] 观察：`adb shell dumpsys meminfo app.mihon.dev`，连续翻页后内存会回落，不持续线性增长。
- [ ] 完成/取消若干任务后执行 `adb shell run-as app.mihon.dev ls cache`；不应持续堆积 `manyue_in_*` 或 `manyue_work_*`。
- [ ] 多次进入/退出 Reader 后，没有重复回调、重复替换或明显 listener 泄漏迹象。

## 6. 回归范围

- [ ] Keiyoushi 扩展仓库仍可添加。
- [ ] 第三方插件仍可安装。
- [ ] Baozi 图源仍可浏览。
- [ ] 下载、历史、书架和章节切换行为无回归。
- [ ] MangaDex“最近更新”仍正常。
- [ ] MangaDex“热门”问题只记录为扩展侧已知问题，不为此修改 Mihon 主体。

## 7. 建议提交的验收证据

- [ ] 设备型号、系统版本、内/外屏分辨率与 `smallestScreenWidthDp`。
- [ ] 一段完整的 `adb logcat | findstr /i "Manyue"` 日志。
- [ ] 四种模式各一张截图。
- [ ] AUTO/FULL/MANUAL 在内屏的截图。
- [ ] 快速翻页、切 OFF、切章节、退出 Reader 四个场景的结果。
- [ ] 长图测试前后两次 `dumpsys meminfo`。
