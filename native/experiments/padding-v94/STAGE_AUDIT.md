# Winograd buffer 阶段时间戳审查

## 范围与结论

只读审查。`work/mihon-source` 当前 HEAD 是 `dbd4cf544e7788eaa225d9e505c403d21c5fc85f`，与指定基线一致；正式 APP 仍为 v92 / base `1dfa768`。未构建、未操作设备、未修改共享源码。代码依据为 `work/quality2/native-profile-v93-20261018/pinned-source`，探针为同目录的 `compute_timestamp_probe.cpp`。

当前方案可在原有 GPU 命令缓冲区中用 8 个时间戳查询把 padding、Winograd 输入变换、GEMM、输出变换分别计时，不需要增加 submit/wait。按 `84 + 8 * top_blob_index` 分配查询槽、并把 query pool 扩为 `84 + 8 * blob_count`，可避免同一提交内多个目标卷积互相覆盖；跨提交复用槽位安全，因为每次 submit 都会重置池。独立 profile 源码应把 `VK_NOT_READY` 映射为非零错误，且不加 `WAIT`；这样 `VK_SUCCESS` 表示读取范围内的查询均可用。

## 43/23 分支、padding 与边界裁剪

- buffer `VkMat` 路径先对正数 padding 调 `padding->forward()`，之后以 bordered 张量计算 `outw/outh`。显式 pad=1 时，420×420 输入先成为 422×422 bordered 张量，卷积输出仍为 420×420（`convolution_vulkan.cpp:1321-1389`）。Winograd 分支选择条件中的 `w/h` 是 bordered 尺寸；43/23 小尺寸切换条件也使用这个尺寸（`1385-1419`）。
- 43 使用 `ceil(outw/4) × ceil(outh/4)` 个输出 tile，每 tile 36 个变换面；23 使用 `ceil(outw/2) × ceil(outh/2)` 个 tile，每 tile 16 个变换面。两条分支均明确依次记录 input transform、GEMM、output transform；输出张量仍创建为精确的 `outw × outh`（`1425-1521`、`1527-1623`）。420 输出对 4 和 2 都整除，故此样本没有不完整的最右/最下 Winograd 输出 tile；一般尺寸的 buffer 输出 shader 会按 `outw/outh` 限制每次 store，例如 43 的 `convolution_pack4_3x3s1d1_winograd43_transform_output.comp:282-299` 和 23 的对应 shader `:170-176`。
- v93 静态报告确认 tile400 加 halo20 后实际输入为420、pad=1 后输出仍420；中间变换宽度分别为 105²=11025（43）和 210²=44100（23），超过该设备的 image3D 限制，因而目标中间卷积落在 buffer 路径（`ADRENO_RESULTS_20261019.zh-CN.md:36`）。源码也在变换张量不满足 image storage shape 时关闭 image storage（43 `convolution_vulkan.cpp:343-356`，23 `:621-634`）。
- 这使 padding 成为一个可独立观测的 GPU 阶段：它在 Winograd input transform 前额外写入 bordered buffer。不能把它并入 input transform 的估时，除非探针明确把阶段定义改成“含 padding 的输入准备”。

## 查询槽与提交边界审查

- `compute_timestamp_probe.cpp:18-19` 定义16个命名边界；`:124-158` 对每个边界各执行一次 `submit_and_wait()`，`:159-169` 又有既存的最终 output-tail 提交。报告中的“16个 chunk”指16个边界区间，tail 是另一次既有提交；阶段探针应保留这条既有路径，不得在中间插入提交或等待（`ADRENO_RESULTS_20261019.zh-CN.md:26,30`）。tail 中包含最后一对中间 Conv/PReLU 和 output head，不能只报告16个边界内的目标卷积（该报告 `:30`）。
- 当前 query pool 的 0..81 是 ncnn 每层两条时间戳的槽，net.cpp 以 `layer_index * 2` 写入（`net.cpp:322-335`），probe 对每个边界读取连续两层的四个 query（`compute_timestamp_probe.cpp:149-153`）。82/83 是 probe 的外层包围区间，probe 在每个边界和最终 tail 都重用它们（`:137-151,161-169`）。因此新增阶段查询从84开始正确隔离了现有层查询与 outer 查询。
- 实际 x2.param 第2行列出41层、42个 blob，第3-43行是层定义；因此 layer index 0..40 正好占 query 0..81，82/83 不与内建层查询冲突（x2.param:2-43）。16个 boundary 56..101 覆盖到 Conv_30/PRelu_31（layer 32/33，q64..67）；Conv_32/PRelu_33（layer 34/35，q68..71）及 output head Conv_34（layer36）落在已有 tail submit。新 stage profile 只应把 Conv_2..Conv_30 和 tail 的 Conv_32 列为64→64目标层；Conv_0 是3→64，Conv_34 是64→12。
- 你的 `84 + 8 * tops[0]` 分配方案在同一提交内也不冲突：每个目标卷积使用连续8槽，分别包围 padding、input transform、GEMM、output transform；pool 设为 `84 + 8 * blob_count` 时，合法性条件是 `top_blob_index < blob_count` 且 `base + 7 < query_count`。42个 blob 对应420个 query，最高使用槽419。`Convolution_vulkan` 继承 `Convolution`，再继承 `Layer`；`Layer::tops` 是 public，因此成员 `forward()` 可直接以 `tops[0]` 计算 base（`convolution_vulkan.h:22`、`convolution.h:22`、`layer.h:152-155`），不需要从 `NetPrivate::forward_layer` 或 `VkCompute` 上下文传递索引。运行时还应断言同一提交内每个 target base 只分配一次，且八次写入齐全。跨提交复用相同槽位没问题，查询池会在每次提交前重置。
- 两种 command 录制模式都会在写 marker 前重置 query pool：非 push-descriptor 路径在 `submit_and_wait()` 开始时 reset（`command.cpp:2295-2302`）；push-descriptor 路径在建池/`reset()` 后的 command buffer 中 reset（`:2571-2575,2538-2545`）。`submit_and_wait()` 之后 fence 已等待（`:2429-2437`），故本次已写 marker 应可读。必须在 `cmd.reset()` 前收集并保存阶段结果；probe 目前也在每次 fence 后读取，再 reset (`compute_timestamp_probe.cpp:145-157`)。pool reset 会清掉上一提交的查询值，所以不能等所有 chunk 结束后再回读。
- 现有 helper 调 `vkGetQueryPoolResults()` 时只传 `VK_QUERY_RESULT_64_BIT`，并把 `VK_NOT_READY` 视作成功返回0（`command.cpp:2580-2596`）。probe 的 `durationGpu()` 用 zero sentinel 拒绝明显缺失的值并按 `timestampValidBits` 做 wrap（`compute_timestamp_probe.cpp:75-82`），但这不等于 availability 检查。独立 profile 源码把 `VK_NOT_READY` 映射为非零错误、不加 `WAIT` 后，只读取本提交中预期写齐的阶段槽；helper 返回 `VK_SUCCESS` 即表示读取范围内全部 query 可用。

## 对 root 方案的建议约束

1. 只在 profile SDK 的 `NCNN_BENCHMARK=1` 构建里启用；仅命中 buffer `VkMat` 的 64→64、3×3、stride=1、四侧显式 pad=1、Winograd 路径。显式确认目标 `VkMat`/image 选路和参数，不覆盖动态 auto-pad 或其他形状；8个 marker 分别紧贴实际 `padding->forward()`、三个 Winograd `record_pipeline()`，不改 shader、算术、调度或网络语义。
2. 保留 q0..81、q82/83 和每个既有 submit/fence；读回阶段 query 时，helper 的 `VK_SUCCESS` 必须保证整个读取范围可用。每一对按 `((end - start) & mask) * period` 换算；从该 Conv 的 layer query 起点解包时间戳，验证 padding/input/GEMM/output 四段的起止顺序单调、均落在 Conv 与 q82/83 outer 区间内，并使阶段合计不超过对应 Conv/outer 区间，最多容忍2个 timestamp tick。chunk与tail分别归属，tail Conv32 单独从 output-tail 提交读取。
3. 对照 profile-off 与 profile-on 记录 output 完全一致、profile gate 命中层/查询槽映射、每层四组查询全可用、16个边界提交与既有tail提交数不变。额外 timestamp 的观测开销应与当前84-query探针的控制比较，并把结果标为 GPU 队列时间，不推断 reader FPS、能耗或正式APP收益。

## 仍可研究的两条降负载方向

- **把 pad=1 的边界零值并入 Winograd 输入变换，省掉 bordered-buffer padding pass。** 代码当前先 materialize padding，再由 input transform 读取（`convolution_vulkan.cpp:1321-1327,1435-1453`）。buffer shader 已有边界读返回零的分支（43 `convolution_pack4_3x3s1d1_winograd43_transform_input.comp:116-141`；23 `:93-111`），因此可以研究只对显式 pad=1 的 buffer 路径调整输入坐标并覆盖四侧越界零值。当前 shader 假设非负起点且第一项读取无下界判断，改动时必须给所有越界读加正确边界保护；目前这只是候选，未证明减时，需逐像素/输出精确比对并用同一提交数复测。
- **若分阶段结果显示43输入/输出变换占比显著，再单独测变换 pipeline 的 local size。** 当前43变换 input/output 固定4×4（`convolution_vulkan.cpp:388-389,466-468`），23固定8×8（`:665-667,744-746`）；既有失败实验否决的是 GEMM workgroup、共享 GEMM、staging 等路线，未证明变换 dispatch 的局部尺寸有收益。只做一个由时间占比引导的 opt-in 变体，不扫参，不与 GEMM 或 shader 数学改动叠加；精确输出、控制开销和同样的 16 边界/tail 约束通过前不宣称收益。

以上两条均为源码推导出的研究假设；现有证据仍支持保留正式 v92。


## Follow-up：Winograd43 pack4 buffer 输入变换 8×8 local-size 候选（静态审查）

候选范围限定为 profile 分支中 Winograd43、buffer `VkMat`、pack4 的 64→64、3×3/stride=1/dilation=1/显式四边 pad=1 卷积；只把 input-transform pipeline 的 local size 从 4×4×1 改为 8×8×1。GEMM、output-transform、padding、shader 算术及命令提交边界保持原样。stage pilot 在 16 个中间卷积上测得 input transform 合计 56.79 ms（GEMM 64.79 ms、output 42.97 ms、padding 15.17 ms；`stage-pilot-20261022T233616.private/report.json:1012-1017`），说明该阶段值得单独测；这些数字不能证明 local-size 变体会更快。

### 启用约束

- 只在隔离的 `NCNN_BENCHMARK=1` stage-profile 源码/库中，且 `MANYUE_STAGE_PROFILE=1` 时启用；关闭该变量时必须仍设置 4×4×1。条件需同时命中 64 个逻辑输入通道与 64 个输出通道、pack4 输入与输出、3×3/s1/d1、四侧 pad 明确为 1，以及运行时确实选择 Winograd43。不能仅依赖“允许 Winograd43”：buffer `VkMat` 路径和 `VkImageMat` 路径各自都有 43 dispatch（`:1285`、`:1422-1453`、`:1757`、`:1887-1918`），而同一个 shader 源含 buffer/image 两种实现（pack4 input shader `:35-41`）。因此还要约束为 buffer 变体（`opt.use_image_storage == false` 且实际调用 `VkMat` 路径），避免 image 路径被环境变量意外改变。
- 只改 input-transform pipeline 的 local size（当前创建点为 `convolution_vulkan.cpp:382-389`），并保留 output/GEMM pipeline 几何和 `VkCompute` 记录顺序。profile-on/off 必须都保留原有16次 chunk submit 及已有 tail submit，不增加 submit、fence 或 wait。关闭 gate 时 pipeline、输出和基线行为应与现有 4×4 一致。

### 线程映射与 workgroup 上限

- shader 只用 `gl_GlobalInvocationID` 形成 `gx/gy/gz`，超出 `block_x/block_y/c` 即早退；tile 起点由 `sx=4*gx`、`sy=4*gy` 决定（`convolution_pack4_3x3s1d1_winograd43_transform_input.comp:56-67`）。每个仍有效的 `(gx,gy,gz)` 读取自己的 6×6 输入窗口；buffer 右/下边界 load 有尺寸判断（`:112-156`），padding pass 已在此前 materialize bordered buffer（`convolution_vulkan.cpp:1321-1327`）。
- buffer 结果地址由 `gz * block_x * block_y + gy * block_x + gx` 形成，再按36个互异 plane 的 `outcstep` 偏移写出（shader `:296-334`）。源码中没有 local invocation ID、shared memory、barrier、subgroup 或 atomic 依赖。因此 8×8只改变全局 tile 分组，不改变有效 invocation 的坐标、读窗或输出地址；按静态索引审查，没有发现因 4×4→8×8 而新增的写冲突/越界路径。dispatch 使用 local size 对 `dispatcher.w/h/c` 向上取整（`command.cpp:1812-1816`），shader 的范围早退覆盖额外 lane。
- 当前设备记录为 subgroupSize=64、maxWorkgroupInvocations=1024、X/Y/Z各维上限1024（`device-gpu-caps-20261019.json:3-7`）。8×8×1共有64 invocation，且各维为8/8/1，远低于记录上限；因此按当前报告的设备能力，workgroup 上限不是正确性阻塞。subgroupSize=64 与一个 8×8 workgroup 的规模相同，但 shader 没有 subgroup 操作，正确性不依赖 subgroup 语义。
- 当前输出宽高420时，Winograd43 的 tile 网格是105×105（`convolution_vulkan.cpp:1425-1427`；样例尺寸见 pilot `report.json:17-18`）。按 ceil-dispatch，每个 Z 面 4×4 会覆盖108×108 invocation 位置，8×8会覆盖112×112；后者因边缘早退多发约7.5%的总 invocation（112²/108²−1），但有效的105×105 tile不变。这是性能侧可能抵消收益的因素，不是结果正确性问题；不要把 group 数减少当成更少 shader 工作的证据。

### 资源风险与验收

输入 shader 每个 invocation 处理6×6个 pack4 值，并计算多个变换中间向量后写36个 plane（shader `:116-156`、`:172-255`、`:296-334`）。8×8不增加单 invocation 的数学或理论临时量，但每个 workgroup 的 invocation 数由16增至64；若临时值保持较长 live range，寄存器分配/占用率可能成为代价。源文件不能给出实际寄存器数、spill 或并发 workgroup 数，故这里只是资源风险，不是已发生的 spill 结论。设备 subgroup 64也不保证更高 occupancy。

建议验收保持单变量：先确认 gate 命中层列表、VkMat/buffer shader、pack4及Winograd43分支；确认 off仍为4×4、on仅input为8×8且GEMM/output几何不变；profile-on/off 逐像素输出完全一致，query全可用，submit/fence/tail数量不变。再以同一输入、同一设备条件做交错重复的 off/on 比较，分别报告输入阶段、whole-Conv和外层GPU时间的分布，并检查异常波动；只有稳定降低且不由观测开销解释时才记录为该 workload 的实测收益。当前为源码审查，未编译、未测设备，也没有对该候选作收益判断。
