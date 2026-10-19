# v93 Winograd GEMM 工作组尺寸审查

审查日期：2026-10-19
基线：`1dfa768bf6ae7aab08637c5e1d0b81f1c2c4964d`，分支 `codex/mihon-native-profile-v93`
审查范围：`pinned-source/src/layer/vulkan/convolution_vulkan.cpp` 与 `pinned-source/src/layer/vulkan/shader/convolution_pack4_3x3s1d1_winograd_gemm.comp`。仅做静态阅读；未改源码、构建或访问设备。

## 结论

对目标配置（64 输入通道到 64 输出通道、pack4、buffer、FP16 storage/FP32 arithmetic、无 cooperative matrix、无 shader local memory），将 Winograd 2x3 与 4x3 GEMM pipeline 的默认工作组尺寸从 `(4,4,4)` 改成 `(8,4,1)`，在静态索引和边界上成立。目标 shader 的 buffer、非 local-memory 分支不读取 `gl_LocalInvocationID`，也不使用 shared memory 或 barrier；每个 invocation 仍独立计算同一组 `(gx, gy, gz)` 输出，内部输入通道求和循环与运算顺序不变。因此预期输出逐像素完全相同，但仍须以设备实测确认。

## 代码依据与边界

- `convolution_vulkan.cpp:427-444` 与 `:705-722` 分别创建 Winograd43、Winograd23 GEMM pipeline。当前 `else` 默认是 `(4, min(4, num_output / out_elempack), 4)`；64→64 pack4 时 `out_elempack=4`、packed 输出通道数为 16，因此实际尺寸为 `(4,4,4)`。
- 同一分支前面分别处理 cooperative-matrix 和 `opt.use_shader_local_memory`。shader 的 `shared` 数组仅在 `NCNN_shader_local_memory` 下定义（`:56-59`）；`gl_LocalInvocationID` 与所有 `barrier()` 也仅在该分支（`:101-193`）。普通 buffer 路径走 `:194-217`，只用全局 ID 与普通 buffer 读写。
- 普通路径的全局坐标为 `gx=gl_GlobalInvocationID.x*4`、`gy=...y`、`gz=...z`（`:61-70`）。buffer GEMM 的 dispatcher 在 `convolution_vulkan.cpp:1475-1493`、`:1577-1595` 设为 `w=ceil(top_tm_blob.w/4)`、`h=top_tm_blob.h`、`c=36`（Winograd43）或 `16`（Winograd23）。`command.cpp:2056-2058` 按 pipeline 的三个 local size 分别向上取整 dispatch 数，故换为 `(8,4,1)` 后仍覆盖所有 dispatcher 坐标；多出的 x/y invocation 由 shader 的边界检查 `:67-70` 丢弃，尾部输出由 `:230-236` 限制写入。
- 64 输出通道 pack4 后 `outc=16`，y 维的 4 个 invocation 与通道块对齐。z 维改成 1 会把 Winograd43 的 z 工作组数从 9 增到 36、Winograd23 从 4 增到 16；这只是把同一批变换系数坐标分到更多工作组，不会漏掉坐标。`Pipeline::set_local_size_xyz` 将尺寸交给 pipeline 创建（`pinned-source/src/pipeline.cpp:114-130`），dispatch 根据同一尺寸计算组数。

## 适用范围需要锁住

目前两个 `else` 分支是通用默认，前面 shader 类型选择包含 pack1、pack4、pack8 及混合 pack 变体（`convolution_vulkan.cpp:401-410`、`:679-688`）。若直接无条件把默认值改为 `(8,4,1)`，影响范围会扩展到所有进入该默认分支的 Winograd GEMM pipeline，而不只目标 64→64 pack4 buffer。建议在 Winograd23 和 Winograd43 两处都只对目标配置选用 `(8,4,1)`，其余保留原默认值；条件至少限定 `!opt.use_image_storage`、FP16 storage 且非 FP16 arithmetic、pack4 输入和输出、`num_input==64 && num_output==64`。cooperative matrix 与 local-memory 分支已有更早的分支，仍应保持原样。

## 预期风险

新尺寸每组 invocation 从 64 降到 32；z 工作组数增加 4 倍，x 工作组数约减半，整体工作组数量约增至两倍（取决于 tile 尾数）。Adreno 的调度开销、占用率和内存合并访问可能因此改善或变差，静态代码无法预测方向。此变更不减少 GEMM 算术量，也不代表整次 400×400 推理会按相同比例加速。现有约 250 ms 完整耗时及约 200 ms wait 只能作为端到端基线；wait 不能当作纯 GPU 时间，需单独记录 GEMM GPU 时间与完整推理时间。

数值风险较低：shader 运算主体及单 invocation 的求和次序没有改变，坐标分配也一一对应。不过新 pipeline 会按新 local size 创建，驱动编译差异仍须用零 RGB 差异门槛排除。

## 最小验证

1. 先用独立 ARM64 新库的 stock-control 模式，对 stock 库跑完全相同的输入与配置，逐像素比较输出；control 必须零 RGB 差异。记录目标层确实命中 64→64 pack4、buffer、FP16-storage/FP32-arithmetic、非 cooperative、非 local-memory GEMM，并记录运行选择了 Winograd23 还是 Winograd43。若要声称两条路径均验证，分别确认两条路径实际命中。
2. 只在上述目标条件命中时启用两处 `(8,4,1)`，其余构建参数和输入保持不变；候选输出对 control 做逐像素比较，要求零 RGB 差异。
3. 在 Honor Magic V2 / Adreno 上做匹配的重复测量，分开报告目标 GEMM 的 GPU 时间与完整 400×400 推理时间，并把 record、reset、wait、tail 作为独立阶段报告。按预设指标要求至少 5% 改善；否则不保留候选。PC 结果不替代 Adreno 验收。
