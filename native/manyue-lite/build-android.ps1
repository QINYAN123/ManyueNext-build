[CmdletBinding()]
param(
    [string]$NcnnRoot,
    [string]$WebpRoot,
    [string]$NdkRoot,
    [string]$WorkRoot,
    [string]$CMakeExe,
    [string]$NinjaExe,
    [string]$BuildDir,
    [string]$JniLibDir
)

$ErrorActionPreference = 'Stop'
$sourceDir = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $sourceDir)
if (-not $WorkRoot) { $WorkRoot = Split-Path -Parent $repoRoot }
$WorkRoot = [System.IO.Path]::GetFullPath($WorkRoot)
if (-not $NcnnRoot) { $NcnnRoot = Join-Path $WorkRoot 'native-deps\ncnn-sdk\ncnn-20241226-android-vulkan-shared' }
if (-not $WebpRoot) { $WebpRoot = Join-Path $WorkRoot 'native-deps\libwebp-source\libwebp-1.5.0' }
if (-not $NdkRoot) {
    $ndkCandidates = @(
        (Join-Path $WorkRoot 'toolchains\android-sdk\ndk\29.0.14206865'),
        'C:\Users\Administrator\Documents\Codex\2026-09-19\ba\work\toolchains\android-sdk\ndk\29.0.14206865'
    )
    $NdkRoot = $ndkCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $NdkRoot) { throw 'Android NDK 29.0.14206865 was not found; pass -NdkRoot explicitly' }
}
if (-not $CMakeExe) { $CMakeExe = Join-Path $WorkRoot 'toolchains\native-build-tools\cmake\data\bin\cmake.exe' }
if (-not $NinjaExe) { $NinjaExe = Join-Path $WorkRoot 'toolchains\native-build-tools\bin\ninja.exe' }
if (-not $BuildDir) { $BuildDir = Join-Path $WorkRoot 'lite-native-build\android-arm64' }
if (-not $JniLibDir) { $JniLibDir = Join-Path $repoRoot 'app\src\main\jniLibs\arm64-v8a' }

foreach ($pair in @(
    @{ Path = $NcnnRoot; Name = 'Pinned ncnn SDK' },
    @{ Path = $WebpRoot; Name = 'libwebp 1.5.0 source' },
    @{ Path = $NdkRoot; Name = 'Android NDK' },
    @{ Path = $CMakeExe; Name = 'CMake executable' },
    @{ Path = $NinjaExe; Name = 'Ninja executable' }
)) {
    if (-not (Test-Path -LiteralPath $pair.Path)) { throw "$($pair.Name) does not exist: $($pair.Path)" }
}
$NcnnRoot = (Resolve-Path -LiteralPath $NcnnRoot).Path
$WebpRoot = (Resolve-Path -LiteralPath $WebpRoot).Path
$NdkRoot = (Resolve-Path -LiteralPath $NdkRoot).Path
$CMakeExe = (Resolve-Path -LiteralPath $CMakeExe).Path
$NinjaExe = (Resolve-Path -LiteralPath $NinjaExe).Path

$ncnnRuntime = Join-Path $NcnnRoot 'arm64-v8a\lib\libncnn.so'
$ncnnHeader = Join-Path $NcnnRoot 'arm64-v8a\include\ncnn\platform.h'
$expectedNcnnSha = '87D150E735157B09AA20F26F5E57F72468C548E7CE98CE407EC50EE7E14A52DD'
if (-not (Test-Path -LiteralPath $ncnnRuntime) -or -not (Test-Path -LiteralPath $ncnnHeader)) {
    throw 'NCNN_ROOT must be the pinned ncnn-20241226-android-vulkan-shared SDK'
}
$actualNcnnSha = (Get-FileHash -LiteralPath $ncnnRuntime -Algorithm SHA256).Hash
if ($actualNcnnSha -ne $expectedNcnnSha) { throw "Pinned libncnn.so hash mismatch: $actualNcnnSha" }
if (-not (Test-Path -LiteralPath (Join-Path $WebpRoot 'src\webp\encode.h'))) {
    throw 'WEBP_ROOT must point to the libwebp 1.5.0 source root'
}
if ((Get-Content -LiteralPath (Join-Path $WebpRoot 'configure.ac') -Raw) -notmatch 'AC_INIT\(\[libwebp\], \[1\.5\.0\]') {
    throw 'WEBP_ROOT is not pinned libwebp 1.5.0'
}

$toolchain = Join-Path $NdkRoot 'build\cmake\android.toolchain.cmake'
$readelf = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $toolchain) -or -not (Test-Path -LiteralPath $readelf)) {
    throw 'Android NDK CMake toolchain or llvm-readelf is missing'
}
$BuildDir = [System.IO.Path]::GetFullPath($BuildDir)
$JniLibDir = [System.IO.Path]::GetFullPath($JniLibDir)
if (-not $JniLibDir.StartsWith([System.IO.Path]::GetFullPath($repoRoot), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to copy output outside this repository: $JniLibDir"
}
$null = New-Item -ItemType Directory -Force -Path $BuildDir
$null = New-Item -ItemType Directory -Force -Path $JniLibDir

$configureArgs = @(
    '-S', $sourceDir,
    '-B', $BuildDir,
    '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$NinjaExe",
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-26',
    '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release',
    "-DMANYUE_LITE_NCNN_ROOT=$NcnnRoot",
    "-DMANYUE_LITE_WEBP_ROOT=$WebpRoot"
)
& $CMakeExe @configureArgs
if ($LASTEXITCODE -ne 0) { throw 'Native CMake configure failed' }
& $CMakeExe --build $BuildDir --target manyue_lite --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'Native Android build failed' }

$built = Join-Path $BuildDir 'libmanyue_lite.so'
if (-not (Test-Path -LiteralPath $built)) { throw "Android shared object was not produced: $built" }
$destination = Join-Path $JniLibDir 'libmanyue_lite.so'
Copy-Item -LiteralPath $built -Destination $destination -Force
$builtHash = (Get-FileHash -LiteralPath $built -Algorithm SHA256).Hash
$copiedHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
if ($builtHash -ne $copiedHash) { throw 'Copied libmanyue_lite.so hash mismatch' }
$header = (& $readelf -h $destination | Out-String)
if ($header -notmatch 'Class:\s+ELF64' -or $header -notmatch 'Machine:\s+AArch64') {
    throw 'Built library is not an AArch64 ELF64 shared object'
}
$dependencies = (& $readelf -d $destination | Select-String 'NEEDED' | ForEach-Object { $_.Line })
if (($dependencies -join "`n") -notmatch '\[libncnn\.so\]') { throw 'Built library does not depend on the pinned libncnn runtime' }
$appNcnn = Join-Path $JniLibDir 'libncnn.so'
if ((Test-Path -LiteralPath $appNcnn) -and (Get-FileHash -LiteralPath $appNcnn -Algorithm SHA256).Hash -ne $expectedNcnnSha) {
    throw 'Existing app libncnn.so no longer matches the pinned runtime'
}

[PSCustomObject]@{
    output = $destination
    bytes = (Get-Item -LiteralPath $destination).Length
    sha256 = $copiedHash
    ncnnSha256 = $actualNcnnSha
    dependencies = $dependencies
} | ConvertTo-Json -Depth 4
