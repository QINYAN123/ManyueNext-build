# Independent pad1/input-transform fusion — before measurement

Input64 pilot was exact but3.48% pooled, below5%; reject it and restore its default4x4 input geometry. No combination with that trial.

New hypothesis from validated stages: materializing the pad1 border costs ~15ms per sixteen middle convolutions and adds logical read/write traffic. The input transform additionally spends ~57ms. Fold only zero pad1 into its coordinates; for a fully in-bounds6x6 tile load directly, otherwise check all four boundaries and return exact zero. Retain transform arithmetic order, precision, weights, output geometry, GEMM/output transform and all17existing submits. This can remove a whole temporary buffer/pass; extra branching/register pressure may instead regress, so no assumed win.

Only integrated Qualcomm, middle64-to64 pack4, buffer, FP16storage/FP32arithmetic, explicit zero symmetricpad1, Winograd43, no shared/cooperative path. MANYUE_FUSE_PAD1=1 opt-in, default off. Specialization constant7 defaults0; original off and image load paths are retained. Forward uses virtual bordered dimensions for algorithm/output shape but real unbordered w/h/cstep for reads. Tiny inputs which chooseWinograd23 continue original padding; no partial patch to23. Odd dimensions must be tested if pilot passes.

Numerical/initial speed gates same as input64: stock versus new-off, then off/on/on/off, warmup2/measured4, finite float and RGB exactly equal. At least5% for both order pairs and pooled improvement exceeding observed control drift before expansion. Also inspect CPU stability and one on/off stage attribution only if gate passes. If colors differ, stop before expanding. Three alternating multi-content/dimension rounds, NCNN_BENCHMARK=0, fullNative seams and true reader/sustained validation are required before application adoption.

Public upstream https://github.com/Tencent/ncnn/wiki/layer-feat-mask also documents that Winograd can trade memory for speed; this is not evidence that disabling it benefits this device. Current experiment preserves Winograd arithmetic and reuses pinned20241226 BSD3 input transform. No broad runtime upgrade or precision/model change.
