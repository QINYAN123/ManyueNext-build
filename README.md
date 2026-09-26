# ManyueNext v9.5 条漫手势与渲染修复

v9.4 确有条漫手势回归：条漫图片不接收触摸，但图片子视图记录了 `DOWN`；随后 `UP` 被 RecyclerView 接走，图片状态一直等待“手势结束”。v9.5 由 RecyclerView 统一管理条漫手势、惯性滚动和缩放状态，并处理取消、离屏返回和解码失败。

## 主要变化

- 修复 AI 状态永久显示“排队中”，分别报告排队、推理和等待画面停稳。
- 条漫替换前保留原图，等新图的基础分块就绪并停稳后再切换；固定同宽页面的项目高度，避免阅读位置跳动。
- PNG/WebP 区域解码使用 Android `BitmapRegionDecoder`，分块边长最多 1024 像素，不让底层默认解码器长期保留整张图片的 RGBA 缓冲区。WebP 区域解码覆盖奇数起点的像素校验和采样尺寸校验；实现参考 [Skia Android `BitmapRegionDecoder` 源码](https://skia.googlesource.com/skia/+/5934f0e64066/client_utils/android/BitmapRegionDecoder.cpp)，并以 PNG/WebP 原生图像测试验证。
- 屏幕内页面保留 AI 优先级；离屏取消的页面再次出现时可以重试。过期解码任务不能改写新图片或传递旧错误。
- 滚动期间推迟启动新的推理和后处理；缓存访问、进程终止和文件清理移出主线程，并避免每帧创建可见页面集合。
- 保留 1.00–2.00× 自定义输出倍率，步进 0.01×。底层模型仍按固定原生 2× 推理，降低输出倍率不会按比例缩短推理时间。
- 裁边沿用现有算法；启用时执行一次临时全图扫描并释放临时 Bitmap/灰度数据。普通区域解码不保留全图 RGBA 数据。固定 2× 结果超过 1200 万像素的长图会保留原图并说明跳过原因。

应用版本为 **0.20.9**，`versionCode` 为 **35**。GitHub Actions 工作流依次应用 v9.1–v9.5 补丁，运行 Manyue 单元测试和 Linux host JNI smoke，再构建签名后的 ARM64 release APK，并检查签名及原生资产。

## 本地准备和 JNI smoke

在全新检出中运行以下命令，源码会准备到 `.build/source`：

```sh
python3 scripts/prepare_manyue_v9_5.py
```

源码准备完成后，可从 delivery 仓库根目录运行 JNI smoke：

```sh
bash scripts/native_crop_jni_smoke.sh .build/source
```

该 smoke 使用 host `g++` 和 JDK JNI headers 编译实际的 crop C++ 源码，并通过 JVM 调用原生库。它覆盖 RGBA 与灰度结果一致性、白边、黑边、混色、空白、无边框，以及灰度方法的尺寸、数组长度和 null 校验；它不测 Android 设备上的速度或帧率。

完整 Android 构建需要 JDK 21、Android SDK、工作流中的 Real-CUGAN 资产准备和 Anime4KCPP 构建步骤。Build kit 只包含基准 `ManyueNext-v9-source.zip`、v9.1–v9.5 补丁、当前 release workflow、`scripts/` 和本 README，不包含其他历史 source ZIP、`.git`、构建目录或签名私钥。

## 验证记录

独立本地回归检查包括 23 项 UI 检查（条漫生命周期 8 项、调度生命周期 4 项、阅读器元数据展示 6 项、区域解码 5 项）和 33 项 AI 核心检查，覆盖并发入队、预取提升/降级、倍率步进和缩放位置转换。这些是本地回归记录，与下方云端 JUnit 统计分开。

GitHub Actions run #23 的云端 JUnit 报告为 **84 项已执行、0 失败、0 错误、0 跳过**。四个必需测试套件分别为：Webtoon 生命周期 8 项、区域解码 5 项、调度生命周期 4 项、阅读器元数据展示 6 项。它们是云端 JVM 报告中的覆盖套件数，不与本地 23 项 UI 检查或 33 项核心回归相加。

已通过的完整 CI 提交为 `5b690045b96780fe1f3a5cb447498a35822af4a7`；[GitHub Actions run #23](https://github.com/QINYAN123/ManyueNext-build/actions/runs/36241525506) 已完成签名、ARM64 APK 和内容检查。该流程没有验证 Android 真机上的视觉效果、GPU 耗时或滑动帧率。

为保留 CI 原始源码包及其校验值，源码 ZIP 中保留了 5 个由 Actions 生成的 Gradle 9.7.1 build-logic 运行缓存文件：`gradle/build-logic/.gradle/9.7.1/executionHistory/` 下的二进制记录和锁文件，以及 `buildOutputCleanup/` 下的锁文件、`cache.properties` 和 `outputFiles.bin`。这些是构建缓存，不是 Gradle wrapper 发行包；可复现 Build kit 内的基准源码 ZIP 不含它们。

## 安装和验收边界

v9.5 使用仓库配置的持久 release 签名。证书 SHA-256 为 `EE3E3B5C0F648507D54D79C9A8ABB772A0448A07F884D0A92EE4599E6F4BAFD7`。此签名与 v9.3/v9.4 不同，**不能直接覆盖安装**。安装前先导出应用内备份并确认文件可用；卸载旧版本会删除应用数据。v9.5 起使用同一持久签名的后续版本可正常覆盖更新。

自动化测试、签名及 APK 内容检查不能代替真机验收。目前没有 Android 真机安装、GPU 耗时或实际滑动帧率结果。原生 GPU 推理仍可能与滚动争用资源，因此不能承诺所有设备都没有掉帧。

恢复备份后，请用曾卡住的同一条漫章节和 AI 设置复核：确认状态依次显示排队、推理、等待停稳和完成；连续上下滚动、返回旧页，并检查图片替换无空白、阅读位置不跳动。切换倍率、裁边和增强模式后重复测试；若某页失败，请提供应用内诊断文本和原图尺寸。
