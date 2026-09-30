# Manyue fixed-2x v9 交接说明

## 当前结论

这份源码已经完成 v9 目标的代码改造和静态检查准备，但当前工作环境没有可用的 Android SDK/Gradle 发行包，也没有荣耀 Magic V2 真机，因此不能在这里诚实地宣称“APK 已编译且真机无报错”。源码内附带的 GitHub Actions 工作流会在 GitHub runner 上下载官方 native 资产、校验 SHA-256、运行 Manyue 单测并生成 ARM64 APK。

## 已实现的行为

- AI 输出固定为原生 2×；不再先缩小后调用模型，也不再在 native 输出后做第二次插值缩放。
- 快速模式：Real-CUGAN `up2x-no-denoise`。
- 质量模式：Real-ESRGAN v3 anime `x2`。
- Anime4KCPP ACNet B4 是可选的后台 CPU/NEON 后处理叠加层，默认关闭；由 `scripts/build_anime4k_android.sh` 从固定上游 commit 构建。它不是 GPU shader，开启会增加出图等待，失败时保留有效 AI 结果。
- 当前页由可见 holder 以优先级 100 排队；阅读器在后台读取并提交后续五页，优先级依次为 50/40/30/20/10。预取源图串行读取/入队，避免五张原图的临时 `ByteArray` 同时占用内存。
- 后台负责图片读取、边界解析、native 推理、WebP 分段、Bitmap 拼接和 `Bitmap.prepareToDraw()` 预热；主线程只安装已解码的 `BitmapDrawable`，并检查 token、页面身份、模式和 generation，避免旧结果覆盖新页面。
- 缓存键包含模型、固定 2× 参数、原图指纹和 Anime4K 开关，切换模型不会误用旧结果。
- 保留 v7 的折叠屏 AUTO/FULL/MANUAL 宽度策略；配置变化后重新套用到已存在的阅读器树，并保留 Webtoon 的首个可见位置和偏移。
- native worker 采用单进程调度、自动 tile 和 `2:4:4` load/proc/save 并发参数，避免每页启动多个模型进程；取消、超时、失败均保留原图并记录尾部日志。

## Native 资产

本地源码包保留原有 Real-ESRGAN 与 ncnn 文件；CUGAN、libomp 和 CUGAN 模型由 `.github/workflows/manyue-fixed2x-v9.yml` 从以下官方 release 下载后注入；Anime4KCPP 从固定的官方源码 commit 通过 Android NDK 编译：

`https://github.com/tumuyan/RealSR-NCNN-Android/releases/download/1.11.1/assets.zip`

Anime4KCPP source: `TianZerL/Anime4KCPP@50c5d1b99965d804eeecfab9b48acfcaffddee3d`.

`scripts/verify_manyue_native.py` 会校验 ARM64 ELF、文件大小和 SHA-256。不要用空文件或自行编译的未校验二进制替代它们。

## GitHub 构建

1. 将整个源码目录内容上传到 GitHub 仓库。
2. 运行仓库中的 `Manyue fixed 2x v9` workflow，或使用 `manyue-v9-final.yml` 的验证构建。
3. 等待原生资源、Manyue 单测、APK 签名和 ARM64 内容检查全部通过。
4. 在该 run 的 `Artifacts` 下载 `manyue-fixed2x-v9-arm64-*`，其中包含 APK 和 `.sha256`。

工作流只使用 `workflow_dispatch`，不会因为普通提交自动消耗构建额度。

## 真机验收（荣耀 Magic V2 / Snapdragon 8 Gen 2）

1. 安装 ARM64 APK，打开阅读器设置，确认显示“AI 超分：原生程序文件完整性已校验”。此项只表示已校验原生文件，不等于已执行模型推理。
2. 选“快速 · Real-CUGAN x2”，打开 AI 超分，确认当前页先显示原图，随后替换为 2× 图；状态栏应出现当前页完成时间、排队耗时、推理耗时。
3. 连续快速翻页，确认当前页优先完成；向后五页应在后台依次出现排队/完成记录。关闭 AI 后应恢复原有高质量渲染器且不触发 native worker。
4. 切换“质量 · Real-ESRGAN x2”重复测试，确认不会显示或复用 CUGAN 的缓存结果。
5. 打开 Anime4K 叠加重复测试；若叠加失败，页面仍应保留 AI 2× 图而非白屏。
6. 在外屏、内屏、半折、旋转和分屏之间切换，确认页面不跳回章节开头，Webtoon 的首个可见位置和滚动偏移保持，内容宽度符合 AUTO/FULL/MANUAL 设置。
7. 观察 `adb logcat` 中是否有 `Manyue native exit code=0`、`Manyue reader replace`；若失败，收集 `manyue_ai_failures/*.log`，不要只依据“按钮可见”判定成功。
8. 记录快速滑动时的掉帧、内存和 GPU 温度。真实 native exit、Vulkan 驱动兼容、替换瞬间是否完全无卡顿，必须以真机结果为准。

## 给下一位执行模型的完整提示词

```text
你接手 ManyueNext fixed-2x v9 源码。目标设备是荣耀 Magic V2（骁龙 8 Gen 2，ARM64）。请先阅读 MANYUE_V9_HANDOFF.md、MANYUE_V9_STATUS.md、scripts/verify_manyue_native.py 和 .github/workflows/manyue-fixed2x-v9.yml，再执行，不要凭空声称 APK 或真机测试已完成。

交付目标：
1. 保持 AI 原生固定 2×，快速模型 Real-CUGAN up2x-no-denoise，质量模型 Real-ESRGAN v3 anime x2；两者都可切换，缓存键必须区分模型和原图指纹。
2. Anime4KCPP 只作为可选叠加后处理，默认关闭；叠加失败必须保留有效 AI 输出。
3. 可见当前页优先级 100；后台预读并送入 AI 的窗口是当前页之后五页，优先级 50/40/30/20/10。当前页的请求创建前，后续预取不得抢占 native worker。
4. 图片读取、边界解析、native 推理、WebP 分段、Bitmap 拼接全部在后台；主线程只做一次已经解码好的 BitmapDrawable 替换，并验证 token/identity/mode/generation。
5. 折叠屏适配必须覆盖外屏/内屏/半折/旋转/分屏/窗口重建，使用实际容器宽度并保持当前章节页和 Webtoon 可见位置/偏移。
6. 运行 `python3 scripts/verify_manyue_native.py`，再运行 Manyue 单测；若本机没有 Gradle/SDK，使用 GitHub Actions 工作流，不得把“源码已改”说成“APK 已验收”。
7. 若 GitHub 写入被拒绝，保留本地源码 zip、补丁、构建工作流和校验说明，并明确报告阻塞原因；不要伪造 release 链接。
8. 最终报告必须区分：静态/单测通过、GitHub 编译通过、APK 安装通过、真机滑动/折叠屏通过；每一项都给出证据。
```
