<!-- adreno-20261019 -->
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

---

# 以下是 2026-10-18 PC 历史，非当前设备状态

# v93 Native 调查结果（未合入性能改动）

本轮应用基线仍是v92 / 1dfa768bf6ae7aab08637c5e1d0b81f1c2c4964d。研发分支codex/mihon-native-profile-v93。手机本轮ADB在线0台，未改变安装包/漫画进度/设置；没有v93 APK、训练或GitHub发布。以下全部为电脑RTX4080 Laptop Vulkan隔离实验，不能称MagicV2提升或节能。

| 路线 | 本轮结果 | 决定 |
|---|---|---|
| 上游local memory关闭 | 42.856→42.579ms，约+0.65%，RGB/float均相同 | 未达预定5%，不采用 |
| 上游pack8 | 42.425→43.149ms，约-1.71%；37796个RGB值差1 | 速度/数值均不通过，不采用 |
| 自定义Conv/PReLU融合 | 新DLL+原graph与旧DLL的float/RGB完全相同；只合并第一对后42215个RGB值差1，float最大差0.00341796875；改用与存储同类的cast仍得到同样差异 | 数值门槛失败，未继续16对/三轮，不合入 |
| 显式staging回读 | 3尺寸×3轮，18次运行，所有输出逐字节相同；电脑blob内存不可映射，stock本就走HOST_CACHED staging，未触发上游缺陷条件 | 不强制加入无收益拷贝；手机仍待查内存属性 |

融合第一轮单pair速度-7.28%，修订轮+3.18%，均仅单轮4个计时样本，不能作为稳定速度结论；两轮都因数值失败而停止。别把1/255差异隐藏成“同画质加速”。packHalf与cast差异不是已证实根因；cast对齐后差异未变，真实原因尚未定位。原参数/权重/精度保持，Conv0/PReLU1、输出head未融合。

数值控制和融合均仅完成400×400彩色输入的首轮筛选；文字、灰阶/网点、完整长图/接缝、16对融合均未执行，不把预生成样本当已测范围。

CPU拒绝探针还出现0xc0000005：loader打印不支持错误后没有安全返回。固定ncnn Net::load_param会在层load失败后continue，存在未赋值layer路径；这是代码层面的疑点，没有崩溃栈证明完整因果。不能把Convolution层返回-1包装成全图安全拒绝；实验graph绝不打包进当前app。未为此扩改通用Net，当前正式v92完全未使用该graph/库。

回读三轮的direct/staged中位数（ms）分别：1380×400为1.480/1.448、1.258/1.195、1.210/1.322；1380×800为2.695/2.533、2.593/2.479、2.596/2.396；2400×800为3.560/3.626、3.818/4.459、3.445/4.398。源不可映射，双方实际走类似中转，本结果没有证明新算法收益。只测已填充buffer拷贝，不是新生成图像的完整Native后处理；不能将几ms的波动外推成约2.4秒网络处理的改善。Windows短线程CPU计时分辨率粗，未通过CPU/能耗验收。

上游#6857/#6864确实修的是record_download，当前RealSR实际使用record_clone；详见download-cache-audit.md。已编译readback_probe-arm64及重新编译修正allocator的compute_probe-arm64，链接stock20241226/ARM64。readback_device.py会校验在线授权、操作者确认空闲Main、推送文件哈希，先跑一对真实RGB字节输出布局；不安装APK、不改缓存和进度，不记录设备标识。它尚未实机运行，不称通过。

下一条：ADB恢复后先现场核对空闲状态，运行回读predicate探针；若源可映射但不HOST_CACHED才进一步完整Native匹配A/B，否则否决强制staging。继续用v92有限阶段证据定位主开销，不再重复本轮四条失败/不适用路线，也不靠更保守温控/全面停AI或换低画质绕过热机目标。持续20页、热稳态、实际Reader Surface帧与可靠功耗仍缺失，目标未完成。

复现路径：同目录PLAN/FUSION_PLAN/READBACK_PLAN、run_host.py/run_fusion.py/run_readback.py、各*.private/report.json。来源与许可证见UPSTREAM_RESEARCH.zh-CN.md；原生fusion补丁为实验失败记录，非可用优化。原始失败、两版patch、5文件hash均保留；公共包不含未授权漫画或私钥。
