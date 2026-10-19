# v93 Adreno 实机调查（2026-10-19）

本轮结论：未找到达到既定收益门槛的性能改动；不发布新 APK，不更换手机的 v92。主目标“持续阅读时少发热、热机仍及时显示正确 AI 图片”仍未完成。本报告是独立模型/张量实验，不是完整阅读器验收。

固定应用基线：`1dfa768bf6ae7aab08637c5e1d0b81f1c2c4964d`，v92 ARM64 benchmark；APK SHA-256 `f5d0ed204a932da7ada29e0df2b6d34366588ccabda0122cd104afbab3925ad4`。本轮现场再次核对手机 APK；没有安装实验库/新包，没有打开 Reader、操作漫画、改动阅读进度/设置或清缓存。最后没有 Native 探针/AI worker 在运行，已回到桌面。源码、模型和正式 Native 资产不变。实验源码可另行提交，不能把研究提交当作 v92 的 APK 构建提交。

## 实测排除的路线

单模型 400×400 CC-BY 彩色样本，每次 2 次预热、4 次测量；候选开/关使用同一重编库，前置 stock 对重编默认关闭控制。保持权重、FP16 存储/FP32 运算、2×输出与 16 个提交边界。首轮预设至少 5% wall 收益及零 RGB 差异才能扩展三轮/多素材/完整 Native/阅读器；CPU 非回归仅作为本实验筛选，非产品资源占用上限。下表是首轮，不能视为稳定提升或节能。

| 路线 | 默认关闭→候选 wall 中位数 | 相对变化 | float/RGB 差异 | 结论 |
|---|---:|---:|---|---|
| GEMM 8×4×1 / 32 线程 | 251.56→298.00ms | 慢 18.46% | 均为 0 | 淘汰 |
| GEMM 8×4×2 / 64 线程 | 262.16→255.00ms | 快 2.73% | 均为 0 | 未达门槛，未证明稳定收益 |
| 固定已知通道常量 | 254.99→248.23ms | 快 2.65% | 均为 0 | 未达门槛，未证明稳定收益 |
| 仅目标 GEMM 启用共享内存 | 252.18→351.34ms | 慢 39.32% | 均为 0 | 淘汰 |

每项的两组控制、逐次样本、CPU、构建/patch 身份均在 JSON。静态通道实验的 stock→重编关闭控制 236.20→277.34ms 波动很大，不能隐藏，也不能用挑选更快一组替代配对。没有将未测的灰阶、文字素材列为通过。没有继续全模型/20 页/热机验证失败候选。

真实 Adreno RGB 字节回读 3,312,000 bytes：源与 staging 内存 flags 均 15，已具有 HOST_CACHED；direct 1.683ms，强制 staging 2.268ms，字节相同。上游“可映射但未缓存”缺陷条件不成立，强行中转无收益。

实际 GPU type=1（集成）、subgroup=64。ncnn20241226 的 Net 将集成 GPU 的全局 shader_local_memory 清为 false，已由运行日志确认，普通开关 A/B 是无效干预；共享内存实验是明确受限的独立 opt-in，结果已否决。storageBufferNoL1 标志不能证明 DRAM 已是当前瓶颈。

## 新取得的 GPU 时间证据

独立 profile SDK 使用 NCNN_BENCHMARK=1，工具与对应库严格匹配。保留原 16 次提交/等待；84 个 query，现有 fence 后取值，不增加 GPU 提交。按 queue 的 validBits/period 校验并处理 wrap，样本无效时停止。

同一 400×400 输入：plain→profile→plain，plain 合并 wall 中位数 256.40ms，profile 255.32ms，表面 -0.42% 开销属于噪声；plain 前后分别 265.29 与 249.05ms，存在约 6% 漂移。输出 float/RGB 相同。不能把负开销宣称加速。

profile wall 255.32ms；16 个 chunk 的 GPU 区间约 189.56ms，尾部网络约 18.51ms。其中前 16 对 Conv/PReLU 的 Conv 约 174.58ms、PReLU 约 14.95ms；这 16 对是 Conv0/PReLU1 加前 15 对中间层，最后一对中间层和输出 head 留在尾部。thread CPU 31.05ms，record 3.34ms、submit/wait 206.18ms、reset 1.38ms、tail wall 41.78ms。

这表明该独立样本的网络 GPU 区间占大头（约 208ms/255ms）；不是整个 APP、完整长图、实际阅读热机或功耗结论。区间包含 GPU 队列执行与停顿，不等于纯 ALU 利用率。不能把 wall 减 GPU 得到的差值都称为 CPU/同步开销，更不能用主窗口帧率代替 Reader Surface。

## Image storage 静态核查

设备 maxImage3D=2048。实际 APP tile400 加 halo20 得到 420 输入；模型 Conv pad=1，空间输出仍 420。Winograd43 的中间 width=105²=11025，Winograd23 为 210²=44100，均超出 3D image 限制；对应中间层会退回 buffer。这里的 420 几何解释不能混同上方无 halo 的独立 400×400 计时输入。

因此单开 image_storage 不能让目标中间卷积自动使用 texture 路径；VkMat extractor 也不能冒充 VkImageMat 端到端实验。未来若重排中间张量布局，属于新 shader/layout 工作，需要单独数值与完整 Native 接缝验证。详见 image-path-audit-20261019.md；没有执行 image 路径性能测试。

## 当前取舍与续接

不采用本轮四个候选，不强制 staging，不将前一天 RGB 不一致的 Conv/PReLU 融合放进 APP，不继续盲调线程形状。实际工作是一名 gpt-6-luna/max 只读审查，root 串行构建与设备实验。没有新权重训练、模型画质评分或 GitHub 发布。

下一步应先利用已有 GPU 标记拆清 Winograd 输入变换、GEMM、输出变换的占比，再决定是否值得写明确受限的 shader/layout 方案；不能以一个内存 flag 代替瓶颈证据。如没有可复核的足够收益，就保留 v92，而不是放宽画质或把温控改保守宣称完成。实际阅读的混合缓存/热状态覆盖率、输入和呈现延迟、持续 20 页与可靠能量数据仍缺失。

各 report.json 的相对路径、哈希、模型与库身份见 ADRENO_RESULTS_20261019.json。本轮仅通过独立探针的数值检查；整体能效验收未通过，也不是完整 Quality2 PASS。旧 RESULTS 文件保留 PC 历史，当前入口以本报告为准。
