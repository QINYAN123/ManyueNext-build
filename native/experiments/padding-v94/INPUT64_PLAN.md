# Input transform64 experiment — fixed before candidate measurement

The valid v94 color pilot measured middle16 convolutions: input56.79ms, output42.97ms, GEMM64.79ms, padding15.17ms; whole183.77ms. Observation overhead0.74%, all float/RGB exact. This motivates ONE input-transform workgroup experiment, not another GEMM sweep.

Change only Winograd43 pack4 input transform local_size4x4x1 to8x8x1 on integrated Qualcomm subgroup64, middle64-to64, FP16 storage/FP32 arithmetic, buffer, no local-memory path. Shader source/math, weights, output size, GEMM/output transform groups, chunk submits unchanged. MANYUE_INPUT64=1 is required; default off. Shader boundary guard rejects rounded excess gx/gy/gz, independent writes/no shared barriers; larger group may hurt register occupancy, so no assumed win.

Initial fixed pilot: same new profile library and same query-enabled tool; stage timing off for wall comparisons; input64 off/on/on/off (each2 warmups+4 measured). Pair first off/on and second on/off, so both order directions are represented. Require exact finite float/RGB versus stock and pair output; each paired on must improve wall by at least5%, pooled improvement must exceed off-bracket drift, no stable CPU regression. If gate fails, stop this direction; no group-size sweep. If gate passes, at least3 alternating rounds across color/line/gray and realistic/odd tile dimensions, then NCNN_BENCHMARK=0 and full Native/reader checks. Separately one stage-enabled disabled/enabled diagnostic may attribute whether input GPU time fell, never use instrumentation timing as final app speed.

All are standalone phone probes on idle launcher. No APK install, progress/cache/settings changes or power estimate from USB charging. Thermal state and battery temperature recorded. Sustained reader/energy conclusion remains open until actual matched validation.

input64.patch is standalone against pristine ncnn20241226. stage-plus-input64.patch is the cumulative diagnostic alternative; never apply both. Original stage-only binary/probe are preserved with their hashes in earlier reports.
