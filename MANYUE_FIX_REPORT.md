# Manyue AI、经典增强与折叠屏修复报告

## 已完成

### 性能改造（本轮）

- RealSR-NCNN 不再固定使用 64px tile；改为 `-t 0`，由 Vulkan 运行库根据实际设备堆内存自动选择 tile，避免在骁龙 8 Gen 2 上把一张普通漫画页拆成数百个小 dispatch。
- 恢复原生 `load:proc:save=1:2:2` 流水线并开启 `-v`，原生日志会记录 GPU、tile 与线程配置；仍保持单个原生进程，避免并发模型造成内存峰值和发热抖动。
- Pager/Webtoon 在可见 Holder 注册任务前先更新调度游标，当前页始终优先级 100；快速前后翻页时不会因为旧页游标而误取消当前页。
- 增加排队等待、原生推理和结果切片/压缩耗时日志，便于真机区分是 IO、排队、Vulkan 推理还是后处理变慢。

### 本次修复（commit `1d6c759`）

- 修正 `ManyueAiUpscaler` 的模型运行目录名为 `models-Real-ESRGANv3-anime`。上游 RealSR-NCNN 会根据目录名识别模型族；此前的 `ai_runtime_anime_dynamic_v3` 不包含 `models`，原生程序因此直接退出并产生 `unknown model dir type`，阅读器才显示“AI 推理失败，已保留原图”。
- 推理失败时保留最近的原生日志到应用缓存，并把退出码/日志尾部传递到页面元数据和 `ManyueDiagnostics`，便于真机继续诊断；仍保留原图回退。

- 从 `tumuyan/RealSR-NCNN-Android` 官方 1.11.1 Release 的 `assets.zip` 恢复 `realsr-ncnn` 与 `libncnn.so`，替换被换行转换截断的文件。
- 为 `.so` 增加 Git binary 属性；增加 `scripts/verify_manyue_native.py`，检查原生程序、ncnn 和模型的 SHA-256，并检查 ELF64、AArch64 与节表边界。
- App 首次使用 AI 前校验安装目录中的原生文件；设置页显示架构、缺失、损坏、不可执行或已验证状态。
- 页面元数据增加真实处理状态：经典处理中、经典成功、AI 排队、AI 成功、组合成功、跳过、失败，并显示安全降级原因。
- AI 创建、哈希、临时输入与结果拼接/压缩移到后台线程；主线程只安装最终图片。
- 同一作品/章节/页/源指纹/目标/代次的运行中请求复用同一 token。可见 Holder 只解绑自己的监听，预取任务不会因 Holder 回收被误杀。
- 预取管理允许同一页索引的不同源指纹并存，避免拆页内容互相取消；相邻章节容器更新不再无条件失效当前任务。
- 组合模式超过经典处理内存上限时保留成功的 AI 图；经典强度 0 不再强制执行 1% 处理。
- 修复 Webtoon Holder 重绑旧回调、旧配置和 `notifyItemRangeChanged` 数量计算错误。
- AI 缓存校验 manifest 片数与连续片名，缓存命中刷新访问时间。
- 增加 AI AUTO / 原生 2× / MANUAL 输出宽度设置，避免把动态目标误称为固定 2×。
- 高质量 WebGPU 渲染器开启时，增强模式明确使用支持 Manyue 的传统 Pager/Webtoon；OFF 恢复 WebGPU。
- 折叠宽度基于真实阅读容器宽度；Pager 用对称内容 inset，Webtoon 用居中 margin，并在 FULL/外屏恢复。

### 构建阻塞修复（commit `99764c0`）

- 修复 `ManyueAiUpscaler.process()` 异常路径中的 Kotlin 可空 `Context` 类型错误。现在进入处理函数时一次性判空，后续缓存、日志保留和失败诊断都使用已确认非空的上下文。

### 最新云端构建

- GitHub Actions 提交：`68b4b3ec2fb93dc8dbf0a6fcf626320a58c7748d`
- Run：`35523679097`，`:app:assembleDebug` 成功，耗时约 4 分 10 秒。
- 构建产物：`ManyueNext-debug-apk-v3`，包含 ARM64、armeabi-v7a、x86、x86_64 和 universal APK；产物摘要：`1c6de8ed87e09b9721682f8f3fd62d108735d884940d6abedc5f43a6325e62a4`。
- ARM64 APK 已确认包含 `assets/ai/models-Real-ESRGANv3-anime/x2.bin`、`x2.param` 及 `libmanyue_realesr.so` / `libncnn.so`。

## 原生文件证据

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| `libmanyue_realesr.so` | 7,835,384 | `d74e2ff5366a3b548118d78d72a4e8e197c764dfda4222a1718c10f50cdece2b` |
| `libncnn.so` | 11,062,000 | `87d150e735157b09aa20f26f5e57f72468c548e7ce98ce407ec50ee7e14a52dd` |
| `x2.bin` | 1,247,368 | `548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d` |
| `x2.param` | 3,173 | `b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04` |

`readelf` 确认 runner 为 ELF64、AArch64、PIE，依赖 `libncnn.so`；两个 ELF 的节表均在文件范围内。

## 本环境已运行

```text
python scripts/verify_manyue_native.py                  PASS
XML parse: base / zh-rCN / zh-rTW strings              PASS
readelf -h -d: runner / ncnn                            PASS
git diff --check                                       PASS
GitHub Actions :app:assembleDebug                       PASS
```

## 仍需真机验收

源码静态检查和云端 APK 构建已通过；仍需在 ARM64 真机上确认 native exit code、实际图像增强效果、失败日志展示、原图回退，以及内外屏/折叠宽度切换：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'eu.kanade.tachiyomi.ui.reader.manyue.*' \
  --tests 'eu.kanade.tachiyomi.ui.reader.metadata.*'
./gradlew :app:assembleDebug
python scripts/verify_manyue_native.py
```

随后严格执行 `DEVICE_TEST_CHECKLIST.md`。源码检查不能替代 ARM64 Vulkan 真机上的 native exit code、图像显示成功和折叠切换验证。

本轮性能改造还需要在荣耀 Magic V2（骁龙 8 Gen 2）上用设置页的 AI 诊断信息确认 `Gpu[...]` 与 `init gpu tilesize`，并比较改造前后的“排队/推理/处理”三段耗时；若系统温控触发降频，应以连续 5 页的中位数为准。
