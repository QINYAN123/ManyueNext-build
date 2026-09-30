# Manyue v9 当前状态

- 代码改造：已完成（固定 2× 双模型、五页预取、后台 Bitmap 替换、Anime4K 可选叠加、折叠屏策略保留）。
- 单测文字与五页优先级：已同步。
- native 资产静态校验脚本：已更新；本地工作区没有新增 CUGAN/Anime4K 二进制，构建时由 GitHub Actions 下载并校验。
- GitHub Actions：已新增手动工作流 `manyue-fixed2x-v9.yml`。
- 本地 Gradle 编译：未通过/不可执行，原因是 Gradle 9.7.1 分发包和 Android SDK 不在可用环境中；没有把这个结果包装成 APK 成功。
- 真实设备验证：未完成。需要 Magic V2 真机检查 Vulkan、native exit code、预取完成时机、替换掉帧、内存和折叠态位置保持。

因此目前可交付的是“可复核源码 + 补丁 + 可运行的 GitHub 构建流程”，不是已经在本地或真机验收过的 APK。
