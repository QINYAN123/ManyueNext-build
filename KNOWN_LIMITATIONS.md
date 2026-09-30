# Manyue 已知限制

1. **仍需真机完成端到端验收。** 当前环境没有 ADB 设备，不能替用户证明 `ProcessBuilder`、Vulkan 驱动、实际图片替换、折叠切换与持续内存表现。具体步骤见 `DEVICE_TEST_CHECKLIST.md`。

2. **本次环境未完成 Gradle 编译。** Wrapper 需要下载 Gradle 9.7.1，但执行环境访问 `services.gradle.org` 返回 `Network is unreachable`。原生文件完整性、ELF、XML、补丁空白错误已在本地验证；Kotlin 编译和 JVM 测试仍需在可联网或已有 Gradle 缓存的构建机运行。

3. **AI 只支持 arm64-v8a。** 非 ARM64 设备会明确显示不支持，经典增强仍可使用。运行前还会校验两个原生文件的 SHA-256，缺失或损坏时保留原图并显示原因。

4. **安全阈值会主动跳过。** 预测原生 2×输出或最终显示 bitmap 超过 12,000,000 pixels 时不启动 AI。组合模式的 AI 图超过 6,000,000 pixels 时保留 AI 结果并跳过经典后处理，不再丢弃已成功的 AI 图。

5. **动画图片不增强。** GIF 与带动画标记的 WebP 保持原阅读行为，并显示跳过状态。

6. **native 超时为 240 秒。** 到时终止进程并保留原图；低性能设备上的极慢页面可能无法完成。

7. **折叠屏识别仍依赖 Android Configuration。** 当前用 `smallestScreenWidthDp >= 600` 判断宽屏内屏，布局计算改用真实阅读容器宽度。若厂商在折叠切换时不更新 Configuration，需要根据真机数据增加 WindowManager/FoldingFeature 路径。

8. **WebGPU 没有直接处理 Manyue 位图。** 当高质量渲染器开启且增强模式不是 OFF 时，Reader 会明确使用支持 Manyue 的传统 Pager/Webtoon；切回 OFF 后恢复 WebGPU。此策略避免保留一个可选择但无效果的入口，也避免在未验证的情况下破坏 WebGPU 的 HDR/增益图路径。

9. **AI 缓存有磁盘上限。** 超过 768 MiB 时回收至约 640 MiB。缓存读取会刷新目录时间，缺片或片号不连续的缓存会失效重建。

10. **当前交付为源码修复包。** 没有在受限环境中生成或签名 APK；不要把源码校验结果写成真机验收通过。
