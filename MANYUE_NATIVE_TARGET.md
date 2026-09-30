# Mihon 原生目标尺寸输出：阶段一

本次从用户提供的 v9.8 验收源码继续开发，保留 Mihon 阅读器和两套现有 2×模型。原图文件保留；AI 模式的输出倍率沿用阅读设置中的 100%–200% 滑条。

## 处理方式

原图 → 固定 2×模型推理 → 原生端按目标宽度缩放 → 编码一次 → Android 校验尺寸 → 磁盘缓存 → 阅读器显示。

- 100% 现在生成原尺寸的增强结果，仍执行完整 2×推理。切换至原图模式才能完全停止 AI。
- 小于 200% 的普通 AI 输出使用面积缩放与 WebP quality 95；200% 跳过额外缩放，使用无损 WebP。
- 面积缩放替代旧的 Android `Bitmap.createScaledBitmap` 过滤，像素结果可能不同。透明图按 alpha 加权，避免透明色污染边缘。
- Android 端不再为这一步完整解码、缩放并重新编码 AI 输出；阅读器为显示而进行的解码仍然存在。
- 原生模型加载、输入/输出、GPU 提交失败会返回错误；Android 不会仅凭一个输出文件就把失败推理记为成功。缓存前还需精确匹配目标宽高。
- 新缓存版本避免复用旧处理结果。旧缓存仍受原有容量上限管理。
- 若开启可选 Anime4KCPP，原生 AI 输出为 PNG，叠加在目标尺寸图上执行，并继续遵守中间图安全预算；叠加失败保留已验证的 AI 输出。

690×1421 原图对应输出如下。目标宽度先按滑条倍率取整，高度再按实际宽度保持比例，使用正数 half-up 取整。

| 滑条 | 输出尺寸 |
|---|---|
| 100% | 690×1421 |
| 125% | 863×1777 |
| 150% | 1035×2132 |
| 200% | 1380×2842 |

这减少输出处理开销，不能把固定 2×模型变成更少运算的 1.25×模型，也不保证当前设备不掉帧。原有 GPU 推理与滚动竞争、每页启动 worker/加载模型等成本仍需设备时间线验证。

## 构建

源码包含已经编译并校验的 ARM64 worker 与现有模型，普通 APK 构建不需要重新编译 worker。需要 JDK 21、Git 和匹配的 Android SDK；在 Windows 上显式设置 `JAVA_HOME`、`ANDROID_HOME`、可写的 `TEMP` 与 `TMP`。解压后的源码没有 `.git` 时，构建脚本会使用 `unknown` 提交标识，不影响编译。

```powershell
python .\scripts\verify_manyue_native.py
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.reader.manyue.*'
.\gradlew.bat --no-daemon :app:assembleBenchmark
```

`benchmark` 是现有 release 派生类型，保留 R8 和资源缩减；应用 ID 为 `app.mihon.benchmark`，版本后缀为 `-benchmark`，可与 `app.mihon` 共存。它使用本机 debug 签名，不是原验收 APK 的 release 签名；数据目录独立。

原生构建输入与脚本见 `native/manyue-target-output/README.md`。两条 worker 增加必须的 `-w <targetWidth>`，并固定 `-s 2`。不要用上游未修改的发行二进制覆盖它们：运行时和打包校验都固定了新文件 SHA-256。自行重编后应验证来源、测试结果和 ABI，再同步 runtime/verifier/第三方说明中的 SHA-256。

## 手机对照

使用同一话、同一批原图和同一阅读速度，关闭 Anime4KCPP，分别测试原版与本次版本的 Real-CUGAN 125%、150%、200%。本次 100% 为另一个原尺寸增强样本，原图模式作为画质基准。再用 Real-ESRGAN 同尺寸对照。

记录逐页实际显示状态与源/输出尺寸、冷启动和连续多页耗时、掉帧及温度。诊断中的“原生”时间包含 worker 启动、模型加载、推理、缩放和编码，不能当作纯神经推理时间。缓存命中和现场生成应分开统计。宿主机测试与 APK 检查不等于荣耀 Magic V2 的 GPU 运行验收。

阶段二研究优先比较可转换的 CARN-M x2 与现有模型；LIIF 连续倍率作为后续候选，需要权重与手机查询实现。此阶段未切换默认模型。
