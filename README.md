# ManyueNext v9.7 条漫跨章与后台负载修复

基于 v9.6 继续修复荣耀 Magic V2 上反馈的无缝跨章增强被取消，以及阅读时后台任务竞争资源的问题。应用版本 **0.20.11**，`versionCode` **37**。

## 改动

- 无缝进入下一章时更新预取窗口，不再把章节变化当成增强设置变化而清空整个 AI 会话；保留仍在屏幕上的有效任务，并显式重建后五页预取。
- 后台预取在滚动期间等待，停止不断读取和复制下一张大图；仍保留当前页和后五页的目标。
- 原生 AI 的 load/proc/save 流水线从 `2:4:4` 收紧为 `1:1:1`；保留自动分块和固定原生 2×，没有用降低分辨率冒充优化。
- 经典增强的 CPU 图像处理改为单 worker 串行执行；关闭增强和 AI 模式会在进入该队列前直接返回，避免和条漫阅读叠加多个全图处理任务。
- 昂贵任务之间根据刚完成的处理耗时安排 0.5–2 秒退让，给阅读器和设备处理前台工作的机会。缓存本身不新增这段休息。
- 原图解码使用全局共享的两条后台线程，AI 分块解码保持单线程。自定义输出倍率、模型选择、经典增强和 Anime4K 选项保持可用。

这些措施降低并发和连续负载，可能让后台五页准备完成得更晚；它们不是固定 GPU 占用百分比限制。已经运行的原生 GPU 工作仍可能与滚动竞争，原图解码器的存活缓冲和纹理上传也仍有成本。没有目标设备帧时间、温度或电源记录，不能据电池健康百分比断言卡顿原因，更不能保证彻底无掉帧。

## 构建

全新检出后：

```sh
python3 scripts/prepare_manyue_v9_7.py
bash scripts/native_crop_jni_smoke.sh .build/source
```

构建套件包含基准源码和 v9.1–v9.7 七个补丁。GitHub Actions 保留 clean、单元测试必需数量门禁、零 lint Error/Fatal 门禁、原生/模型校验、签名 release 构建及 APK 检查。实际结果和证据见发布附件中的完整验收报告。

### v9.7 CI 结果

GitHub Actions [run 36341396602](https://github.com/QINYAN123/ManyueNext-build/actions/runs/36341396602) 对已测试交付提交 `bca8c64869ebaa39644c766469283c9238bf4270` 成功：21 个 JUnit suite 共 **107 项，0 failure、0 error、0 skipped**；Webtoon lifecycle 14、scheduler 6、prefetch coordinator 3、AI runtime 11。Lint 为 **0 Error/Fatal、70 Warning**，Warning issue ID 计数与 v9.6 一致。清洁构建、native/model integrity、持久证书 ARM64 APK 构建与交付门禁均通过。

APK SHA-256：`ca6ee231fb2ce5ad4121ffa44a8212e482d48e6fa4536fc97a1189b88b6bf617`。源码 ZIP SHA-256：`21ebc5fa94e07b953c61da3c0d859506c9ebbe8a929397357b18ca01b93fefb3`。这些结果证明 CI 和产物检查通过，不代表 Honor Magic V2 真机、完整 Viewer 集成或性能验收完成。

## 安装

沿用 v9.5/v9.6 持久签名，可覆盖安装。证书 SHA-256：`EE3E3B5C0F648507D54D79C9A8ABB772A0448A07F884D0A92EE4599E6F4BAFD7`。

固定 2× 输出仍有 1200 万像素安全预算，经典增强 600 万像素，Anime4K 叠加输入 300 万像素。此次修复的“移出预读范围”是章节调度取消问题，与这些图像尺寸保护是不同路径。
