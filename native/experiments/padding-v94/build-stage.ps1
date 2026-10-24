param([int]$Jobs=6)
$ErrorActionPreference='Stop'
$stageDir94=$PSScriptRoot
$stageRoot94=[IO.Path]::GetFullPath((Join-Path $stageDir94 '..\..\..'))
$stageNdk94='C:/Users/Administrator/Documents/Codex/2026-09-19/ba/work/toolchains/android-sdk/ndk/29.0.14206865'
$stageCmake94=Join-Path $stageRoot94 'work/toolchains/native-build-tools/cmake/data/bin/cmake.exe'
$stageNinja94=Join-Path $stageRoot94 'work/toolchains/native-build-tools/bin/ninja.exe'
$stagePrefix94=Join-Path $stageDir94 'stage-arm64-prefix'
& $stageCmake94 -S (Join-Path $stageDir94 'stage-source') -B (Join-Path $stageDir94 'stage-arm64-build') -G Ninja "-DCMAKE_MAKE_PROGRAM=$stageNinja94" "-DCMAKE_TOOLCHAIN_FILE=$stageNdk94/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release "-DCMAKE_INSTALL_PREFIX=$stagePrefix94" -DNCNN_VERSION=20241226 -DNCNN_VULKAN=ON -DNCNN_SIMPLEVK=ON -DNCNN_SHARED_LIB=ON -DNCNN_OPENMP=ON -DNCNN_RUNTIME_CPU=ON -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_BENCHMARK=ON -DNCNN_INSTALL_SDK=ON *> (Join-Path $stageDir94 'configure.log')
if($LASTEXITCODE -ne 0){throw 'stage configure failed'}
& $stageCmake94 --build (Join-Path $stageDir94 'stage-arm64-build') --target ncnn --parallel $Jobs *> (Join-Path $stageDir94 'build.log')
if($LASTEXITCODE -ne 0){throw 'stage build failed'}
& $stageCmake94 --install (Join-Path $stageDir94 'stage-arm64-build') *> (Join-Path $stageDir94 'install.log')
if($LASTEXITCODE -ne 0){throw 'stage install failed'}
$stageClang94=Join-Path $stageNdk94 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
& $stageClang94 --target=aarch64-linux-android26 -std=c++11 -O2 -fPIE -pie -static-libstdc++ (Join-Path $stageDir94 'compute_stage_probe.cpp') "-I$stagePrefix94/include/ncnn" "-L$stagePrefix94/lib" -lncnn -lvulkan -llog -landroid -ldl -o (Join-Path $stageDir94 'compute_stage_probe-arm64') *> (Join-Path $stageDir94 'probe-build.log')
if($LASTEXITCODE -ne 0){throw 'stage probe build failed'}
Get-FileHash (Join-Path $stagePrefix94 'lib/libncnn.so'),(Join-Path $stageDir94 'compute_stage_probe-arm64') -Algorithm SHA256 | Select-Object Hash,Path
