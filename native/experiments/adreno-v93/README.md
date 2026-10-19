# Isolated Adreno investigations, not production

No accepted performance change. Application baseline remains v92 / 1dfa768bf6ae7aab08637c5e1d0b81f1c2c4964d. See ADRENO_RESULTS_20261019.zh-CN.md and JSON for measured scope, controls, failures and identities. These files are not wired into the app build. No APK or trained weights are produced here.

Pinned upstream: ncnn20241226 full source archive SHA256 b2145f7b01422f5ee66777d1c2c29de935191ed39865193951e1a7228202a6c1 (https://github.com/Tencent/ncnn/releases/tag/20241226). BSD-3-Clause license copied alongside patches. Four patches are alternative cumulative revisions against this pristine source; NEVER stack them. All require explicit opt-in environment flags. All four failed pilot speed gates. Do not adopt for production.

Same-computer reproducibility workspace: work/quality2/native-profile-v93-20261018 at task root. It retains pristine source, NDK29/API21 Release CMake configure/build logs, matching plain and NCNN_BENCHMARK=1 profile SDKs, binaries, raw numerical reports and workgroup_device.py/timestamp_device.py/readback_device.py. Only root schedules a device run after checking actual foreground/user state. Existing build script points at the latest experimental source; it does NOT automatically reconstruct an earlier result's library.

compute_timestamp_probe.cpp is derived from ../conv-prelu-v93/compute_probe.cpp and must link/run against the matching NCNN_BENCHMARK=1 SDK/library. Never use a plain no-query-pool tool with the profile library, or a profile tool with the stock library. Output equality and observation overhead were checked before interpreting GPU intervals. It profiles an isolated tensor, not reader frames or power.

Representative standalone source compilation: NDK29 clang++ --target=aarch64-linux-android26 -std=c++11 -O2 -fPIE -pie -static-libstdc++, matching ncnn headers and library, -lvulkan -llog -landroid. The ncnn library itself is built with Android API21, c++_static, Vulkan/SIMPLEVK, OpenMP and runtime CPU enabled. Check saved CMake logs for complete options. The profiles and 400px tensor geometry do not establish a production Native/heat benefit.
