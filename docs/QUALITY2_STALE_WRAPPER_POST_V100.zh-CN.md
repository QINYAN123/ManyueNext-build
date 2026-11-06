# v100 之后：WebGPU 页缓存同键旧槽加固（未提交）

日期：2026-11-06。基于 `codex/mihon-recovery-v100`（a4865f14）工作区，**未提交、未 assemble、未装机**（本机 PATH 无 git，未连接手机）。

## 改动

对应 `DUPLICATE_PAGE_AUDIT_20261103` 候选 1：`WebGpuViewer.getPage(ReaderPage)` 命中 `(chapterId, index)` 后直接复用旧 wrapper，不核对 ReaderPage 身份。章节被回收后重新进入时会生成新的 ReaderPage，旧 wrapper 会继续持有旧页表、旧 loader stream 和旧 AI 状态。

- `WebGpuViewer.kt`：只有在缓存的 wrapper 持有**同一个** ReaderPage 实例时才复用。不是同一实例时，记录一行 INFO 日志 `replaced stale page wrapper chapter=… index=…`，按淘汰路径释放旧 wrapper，再新建 wrapper。当前显示页和翻页动画 pin 住的页不替换，保持原行为，避免闪烁和空白；它们离屏后会自然淘汰。
- 淘汰逻辑抽成 `dropFromCache()`，`evictFarthestPage` 和上面的替换路径共用，行为不变。
- 新增 `WebGpuPageCachePolicy`（纯函数）和 `WebGpuPageCachePolicyTest`（4 项）。

正常阅读时同一章的 ReaderPage 实例保持稳定，所以这条替换路径不会触发。

## 验证

- `:app:testDebugUnitTest --offline`：524/524 通过（原 520 项 + 新增 4 项），0 失败。
- `:app:spotlessCheck` 失败，原因是已有的 89 个文件格式不合规（例如 `CloudFavoritesScreen.kt`、`ManyueAiRequestRejectionTest.kt`）。本次没有批量格式化。
- 没有真机验证。不能证明这条路径就是用户看到重复页的原因；`replaced stale page wrapper` 日志可以作为下次真机复现时的轻量归因信号。

## 未做

实际重复页归因、Native 早取实验、热 3 持续性能：都需要手机，按 ACTIVE_PLAN 顺序保留。
