# ManyueNext 内容显示与阅读流程改造

这份源码快照对应同目录交付的 APK，基于 ManyueNext/Mihon fork 工作树构建。

## 已实现

- 独立内容属性：漫画与图源分别支持 `AUTO / 正常 / 成人`，不占用普通分类。
- AUTO 默认开启：按已有图源 warning、稳定来源信号和规范化 genre 标签识别；成人内容默认隐藏。
- 会话级“显示隐藏内容”开关；关闭 AUTO 后不再自动隐藏，仍保留手动成人覆盖。
- 书架、图源、历史、更新、云收藏、图源浏览、全局搜索和迁移搜索统一使用显示策略。
- 书架长按批量设置内容属性；漫画详情和图源长按均可修正。
- 书架从收藏漫画 genre 聚合标签，并提供单标签筛选，与普通分类保持独立。
- 书架点击与“继续”按最近实际阅读记录恢复章节和页码；无记录时沿用原生开始阅读逻辑，并对越界页码夹取。
- 阅读器顶栏显示原图尺寸与最终实际图像尺寸；增强尺寸来自最终 Bitmap，不使用倍率乘法。
- 阅读器右上角新增目录按钮，复用现有漫画详情/章节目录导航。
- Manga 数据库字段、备份恢复和迁移链路已接入内容属性。

## 验证

在 Windows 工作树中使用 JDK 21、Android SDK 和 Gradle wrapper 执行：

```powershell
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

本次交付已通过全量 `:app:testDebugUnitTest` 和 `:app:assembleDebug`。没有连接真机做 UI 自动化验收；APK 是 debug 签名包，不能保证覆盖安装到使用其他签名的正式包。

源码包排除了 `.git`、Gradle/build 缓存、`local.properties`、本机输出目录和工作区内部临时目录，但保留了本次工作树中实际存在的源码、配置、内置连接器、Gradle wrapper、迁移和测试文件。
