# ManyueNext v9.4

此修复基于 `codex/manyue-v9-final` 的成功构建 v9.3（提交 `7c59efaa8f681c92b6a860ce398d3e280ab32a3c`），保留 Real-CUGAN、Real-ESRGAN、Anime4KCPP 和分块图片预加载功能。

- 恢复自定义超分倍率：1.00–2.00×，步进 0.01×，默认保持原来的 2×。设置持久化，倍率变化使旧任务失效，不同输出尺寸隔离缓存。1.00×直接保留原图。
- 两种 AI 模型均保留原图进行原生 2×推理，再对自定义倍率输出做后台缩放；不先缩小原图。2×输出继续直接复用原生文件。自定义小倍率不代表原生推理时间会按倍率下降。
- 当前页复用预渲染结果时提升优先级；同步入队与任务领取，避免同一任务被重复推理。优先级降低时仍正确重新排序。
- 模型文件完成校验后在当前进程内复用，不逐页计算模型哈希；完成回调不在主线程检查磁盘缓存。
- 高清图切换保留当前缩放和阅读中心，避免重新执行横图自动缩放；翻页阅读器和条漫均等待拖动/缩放结束，条漫继续等待 RecyclerView 停止滚动。
- 修正排队耗时统计，排队时间不再包含推理和缓存写入时间。新增倍率、视口转换和并发调度回归测试。

## 构建

GitHub Actions 的 `.github/workflows/manyue-v9-final.yml` 自动执行补丁链、下载并校验 Real-CUGAN 资产、构建 Anime4KCPP、运行 Manyue 测试并生成 ARM64 release APK。APK 名称为 `ManyueNext-v9.4-arm64-release.apk`，版本为 0.20.8，versionCode 为 34。

仅准备源码，可在含 Git 元数据的新检出中运行：

```sh
python scripts/prepare_manyue_v9_4.py
```

源码位于 `.build/source`。完整本地构建需要 JDK 21、Android SDK、工作流中的 Real-CUGAN 资产准备步骤和 Anime4KCPP 构建步骤。不要把旧 v8 补丁应用到 v9 源码；`manyue_v9.4_scale_render.patch` 应用在 v9.3 之后。

## 已执行验证与边界

独立 Kotlin 编译已检查 AI 调度、请求、缓存、运行状态、页面桥接和 ReaderPageImageView，与缓存中的 Android/Coil/SSIV 类库链接；无关的项目偏好和日志扩展使用桩。33 项独立 JVM 回归检查通过，包含并发入队、预取提升/降级、倍率步进和缩放位置转换。完整补丁链还需通过 fresh checkout 验证。

本机 Gradle 在项目配置之前被 `java.io.IOException: Unable to establish loopback connection` 阻断。此次修复没有完成新的完整 Gradle 构建、APK 安装或真机帧率测试；旧 v9.3 的云端构建成功不能当作 v9.4 的构建结果。

真机验收：在 Pager 和 Webtoon 中分别测试 1.00/1.25/1.51/2.00×，重开设置确认保存，推理中快速翻页、换章、切换模型，图片替换时缩放和滚动，核对阅读位置、错页保护、失败时保留原图与内存。帧率和 GPU 推理耗时需用相同页面与设备对比。自定义倍率仍受原生 2×和现有像素安全预算限制。
