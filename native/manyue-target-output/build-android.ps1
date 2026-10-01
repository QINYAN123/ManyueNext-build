[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$UpstreamZip,
    [Parameter(Mandatory = $true)][string]$NcnnRoot,
    [Parameter(Mandatory = $true)][string]$WebpRoot,
    [Parameter(Mandatory = $true)][string]$NdkRoot,
    [Parameter(Mandatory = $true)][string]$WorkRoot,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [string]$CMakeExe,
    [string]$NinjaExe,
    [string]$PythonLauncher = "py"
)

$ErrorActionPreference = "Stop"

function Require-Path([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Description does not exist: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

$upstreamZipPath = Require-Path $UpstreamZip "Pinned upstream source ZIP"
$ncnnRootPath = Require-Path $NcnnRoot "ncnn SDK root"
$webpRootPath = Require-Path $WebpRoot "libwebp source root"
$ndkRootPath = Require-Path $NdkRoot "Android NDK root"
$workRootPath = [System.IO.Path]::GetFullPath($WorkRoot)
$outputDirPath = [System.IO.Path]::GetFullPath($OutputDir)
$null = New-Item -ItemType Directory -Force -Path $workRootPath
$null = New-Item -ItemType Directory -Force -Path $outputDirPath

$expectedUpstreamSha = "595B505EB41D84ABB80D7EAB49886F485B2FC70E218CE9632B72F9B2747ED736"
$actualUpstreamSha = (Get-FileHash -LiteralPath $upstreamZipPath -Algorithm SHA256).Hash
if ($actualUpstreamSha -ne $expectedUpstreamSha) {
    throw "Unexpected upstream ZIP SHA-256. Expected $expectedUpstreamSha, got $actualUpstreamSha"
}

$ncnnLibrary = Join-Path $ncnnRootPath "arm64-v8a\lib\libncnn.so"
$ncnnHeader = Join-Path $ncnnRootPath "arm64-v8a\include\ncnn\platform.h"
$expectedNcnnSha = "87D150E735157B09AA20F26F5E57F72468C548E7CE98CE407EC50EE7E14A52DD"
if (-not (Test-Path -LiteralPath $ncnnLibrary) -or -not (Test-Path -LiteralPath $ncnnHeader)) {
    throw "NCNN_ROOT must be the ncnn-20241226-android-vulkan-shared package root"
}
$actualNcnnSha = (Get-FileHash -LiteralPath $ncnnLibrary -Algorithm SHA256).Hash
if ($actualNcnnSha -ne $expectedNcnnSha) {
    throw "Unexpected ncnn runtime SHA-256. Expected $expectedNcnnSha, got $actualNcnnSha"
}

$webpCmake = Join-Path $webpRootPath "CMakeLists.txt"
$webpConfig = Join-Path $webpRootPath "configure.ac"
if (-not (Test-Path -LiteralPath $webpCmake) -or -not (Test-Path -LiteralPath (Join-Path $webpRootPath "src\webp\encode.h"))) {
    throw "WEBP_ROOT must point to the libwebp 1.5.0 source root"
}
if ((Get-Content -LiteralPath $webpConfig -Raw) -notmatch 'AC_INIT\(\[libwebp\], \[1\.5\.0\]') {
    throw "WEBP_ROOT is not the pinned libwebp 1.5.0 source"
}

if (-not $CMakeExe) {
    $CMakeExe = (Get-Command cmake.exe -ErrorAction SilentlyContinue).Source
}
if (-not $CMakeExe) {
    $workspaceWork = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
    $candidate = Join-Path $workspaceWork "toolchains\native-build-tools\cmake\data\bin\cmake.exe"
    if (Test-Path -LiteralPath $candidate) { $CMakeExe = $candidate }
}
if (-not $CMakeExe -or -not (Test-Path -LiteralPath $CMakeExe)) {
    throw "Set -CMakeExe to a CMake 3.22+ executable"
}
if (-not $NinjaExe) {
    $NinjaExe = (Get-Command ninja.exe -ErrorAction SilentlyContinue).Source
}
if (-not $NinjaExe) {
    $workspaceWork = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
    $candidate = Join-Path $workspaceWork "toolchains\native-build-tools\bin\ninja.exe"
    if (Test-Path -LiteralPath $candidate) { $NinjaExe = $candidate }
}
if (-not $NinjaExe -or -not (Test-Path -LiteralPath $NinjaExe)) {
    throw "Set -NinjaExe to a Ninja executable"
}
$NinjaExe = (Resolve-Path -LiteralPath $NinjaExe).Path

$pythonCommand = Get-Command $PythonLauncher -ErrorAction SilentlyContinue
if (-not $pythonCommand) { throw "Python launcher not found: $PythonLauncher" }
$pythonArgs = @()
if ([System.IO.Path]::GetFileNameWithoutExtension($pythonCommand.Source) -ieq "py") {
    $pythonArgs = @("-3")
}
$PythonLauncher = $pythonCommand.Source

$toolchain = Join-Path $ndkRootPath "build\cmake\android.toolchain.cmake"
if (-not (Test-Path -LiteralPath $toolchain)) { throw "NDK CMake toolchain not found: $toolchain" }
$helperDir = $PSScriptRoot
$scriptPath = Join-Path $helperDir "patch_upstream.py"
$templatePath = Join-Path $helperDir "CMakeLists.android.in"
$resizeHeader = Join-Path $helperDir "manyue_output_resize.h"
$statusHeader = Join-Path $helperDir "manyue_ncnn_status.h"
$fileOutputHeader = Join-Path $helperDir "manyue_file_output.h"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourceRoot = Join-Path $workRootPath "upstream"

if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot "RealSR-NCNN-Android-1.11.1\RealSR-NCNN-Android-CLI"))) {
    $null = New-Item -ItemType Directory -Force -Path $sourceRoot
    Expand-Archive -LiteralPath $upstreamZipPath -DestinationPath $sourceRoot
}
$extractedSource = Join-Path $sourceRoot "RealSR-NCNN-Android-1.11.1"
if (-not (Test-Path -LiteralPath $extractedSource)) { throw "The pinned source ZIP has an unexpected root directory" }

$runnerSpecs = @(
    @{ Variant = "realesr"; Directory = "realesr"; Target = "RealSR"; Output = "libmanyue_realesr.so" },
    @{ Variant = "realcugan"; Directory = "realcugan"; Target = "RealCUGAN"; Output = "libmanyue_realcugan.so" }
)

foreach ($spec in $runnerSpecs) {
    $prepared = Join-Path $workRootPath ("patched\" + $spec.Directory)
    & $PythonLauncher @pythonArgs $scriptPath --source-root $extractedSource --destination $prepared `
        --variant $spec.Variant --helper-header $resizeHeader --status-header $statusHeader `
        --file-output-header $fileOutputHeader `
        --cmake-template $templatePath
    if ($LASTEXITCODE -ne 0) { throw "Source patch failed for $($spec.Variant)" }

    $buildDir = Join-Path $workRootPath ("build\" + $spec.Directory)
    $configureArgs = @(
        "-S", $prepared,
        "-B", $buildDir,
        "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=$NinjaExe",
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-26",
        "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DNCNN_ROOT=$ncnnRootPath",
        "-DWEBP_ROOT=$webpRootPath"
    )
    & $CMakeExe @configureArgs
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed for $($spec.Variant)" }
    & $CMakeExe --build $buildDir --parallel 4
    if ($LASTEXITCODE -ne 0) { throw "CMake build failed for $($spec.Variant)" }

    $built = Join-Path $buildDir $spec.Output
    if (-not (Test-Path -LiteralPath $built)) { throw "Built runner is missing: $built" }
    $destination = Join-Path $outputDirPath $spec.Output
    Copy-Item -LiteralPath $built -Destination $destination -Force
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    Write-Output "$($spec.Output) $destination SHA256=$hash"
}

$readelf = Join-Path $ndkRootPath "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (Test-Path -LiteralPath $readelf) {
    foreach ($name in @("libmanyue_realesr.so", "libmanyue_realcugan.so")) {
        $path = Join-Path $outputDirPath $name
        Write-Output "--- $name ELF header ---"
        & $readelf -h $path | Select-String "Class:|Machine:"
        Write-Output "--- $name dependencies ---"
        & $readelf -d $path | Select-String "NEEDED"
    }
}
