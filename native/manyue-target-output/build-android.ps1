[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$UpstreamZip,
    [Parameter(Mandatory = $true)][string]$NcnnRoot,
    [Parameter(Mandatory = $true)][string]$WebpRoot,
    [Parameter(Mandatory = $true)][string]$NdkRoot,
    [Parameter(Mandatory = $true)][string]$WorkRoot,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [ValidateSet("realesr", "realcugan", "both")][string]$Engine = "realesr",
    [switch]$ColdCli,
    [switch]$GpuChunk,
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

function Test-PathWithin([string]$Path, [string]$Parent) {
    $pathFull = [System.IO.Path]::GetFullPath($Path)
    $parentFull = [System.IO.Path]::GetFullPath($Parent).TrimEnd(
        [char[]]@([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    )
    if ($pathFull.Equals($parentFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    $prefix = $parentFull + [System.IO.Path]::DirectorySeparatorChar
    return $pathFull.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
}

$repoRootPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$upstreamZipPath = Require-Path $UpstreamZip "Pinned upstream source ZIP"
$ncnnRootPath = Require-Path $NcnnRoot "ncnn SDK root"
$webpRootPath = Require-Path $WebpRoot "libwebp source root"
$ndkRootPath = Require-Path $NdkRoot "Android NDK root"
$workRootPath = [System.IO.Path]::GetFullPath($WorkRoot)
$outputDirPath = [System.IO.Path]::GetFullPath($OutputDir)

if (Test-PathWithin $workRootPath $repoRootPath) {
    throw "WorkRoot must be outside the Mihon repository: $workRootPath"
}
if (Test-PathWithin $outputDirPath $repoRootPath) {
    throw "OutputDir must be outside the Mihon repository; this helper does not update app assets: $outputDirPath"
}

$null = New-Item -ItemType Directory -Force -Path $workRootPath
$workRootPath = (Resolve-Path -LiteralPath $workRootPath).Path
if (Test-PathWithin $workRootPath $repoRootPath) {
    throw "Resolved WorkRoot must be outside the Mihon repository: $workRootPath"
}
$workRootItem = Get-Item -LiteralPath $workRootPath -Force
if (($workRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "WorkRoot may not be a junction or symbolic link: $workRootPath"
}

$null = New-Item -ItemType Directory -Force -Path $outputDirPath
$outputDirPath = (Resolve-Path -LiteralPath $outputDirPath).Path
if (Test-PathWithin $outputDirPath $repoRootPath) {
    throw "Resolved OutputDir must be outside the Mihon repository: $outputDirPath"
}
$outputDirItem = Get-Item -LiteralPath $outputDirPath -Force
if (($outputDirItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "OutputDir may not be a junction or symbolic link: $outputDirPath"
}

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
    $cmakeCommand = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if ($cmakeCommand) { $CMakeExe = $cmakeCommand.Source }
}
if (-not $CMakeExe -or -not (Test-Path -LiteralPath $CMakeExe)) {
    throw "Set -CMakeExe or put CMake 3.22+ on PATH"
}
$CMakeExe = (Resolve-Path -LiteralPath $CMakeExe).Path

if (-not $NinjaExe) {
    $ninjaCommand = Get-Command ninja.exe -ErrorAction SilentlyContinue
    if ($ninjaCommand) { $NinjaExe = $ninjaCommand.Source }
}
if (-not $NinjaExe -or -not (Test-Path -LiteralPath $NinjaExe)) {
    throw "Set -NinjaExe or put Ninja on PATH"
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
$patchScript = Join-Path $helperDir "patch_upstream.py"
$residentScript = Join-Path $helperDir "prepare_resident_session.py"
$gpuChunkScript = Join-Path $helperDir "patch_gpu_chunk.py"
$templatePath = Join-Path $helperDir "CMakeLists.android.in"
$resizeHeader = Join-Path $helperDir "manyue_output_resize.h"
$statusHeader = Join-Path $helperDir "manyue_ncnn_status.h"
$fileOutputHeader = Join-Path $helperDir "manyue_file_output.h"
$fastPngHeader = Join-Path $helperDir "manyue_fast_png.h"
$protocolHeader = Join-Path $helperDir "manyue_resident_protocol.h"
foreach ($requiredFile in @(
    $patchScript, $residentScript, $gpuChunkScript, $templatePath,
    $resizeHeader, $statusHeader, $fileOutputHeader, $fastPngHeader, $protocolHeader
)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Native source generator input is missing: $requiredFile"
    }
}

$runId = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
$runRoot = Join-Path $workRootPath ("runs\" + $runId)
if (-not (Test-PathWithin $runRoot $workRootPath)) {
    throw "Generated run path escaped WorkRoot: $runRoot"
}
$null = New-Item -ItemType Directory -Force -Path $runRoot
$runRoot = (Resolve-Path -LiteralPath $runRoot).Path
if (-not (Test-PathWithin $runRoot $workRootPath) -or
    ((Get-Item -LiteralPath $runRoot -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Resolved generated run path is not a normal directory under WorkRoot: $runRoot"
}

$sourceRoot = Join-Path $runRoot "upstream"
$null = New-Item -ItemType Directory -Force -Path $sourceRoot
Expand-Archive -LiteralPath $upstreamZipPath -DestinationPath $sourceRoot
$extractedSource = Join-Path $sourceRoot "RealSR-NCNN-Android-1.11.1"
if (-not (Test-Path -LiteralPath (Join-Path $extractedSource "RealSR-NCNN-Android-CLI\RealSR\src\main\jni\main.cpp"))) {
    throw "The pinned source ZIP has an unexpected root directory"
}

$runnerSpecs = @(
    @{ Variant = "realesr"; Directory = "realesr"; Target = "RealSR"; Output = "libmanyue_realesr.so"; EngineSource = "realsr.cpp" },
    @{ Variant = "realcugan"; Directory = "realcugan"; Target = "RealCUGAN"; Output = "libmanyue_realcugan.so"; EngineSource = "realcugan.cpp" }
)
$selectedSpecs = @($runnerSpecs | Where-Object {
    $Engine -eq "both" -or $_.Variant -eq $Engine
})
if ($selectedSpecs.Count -eq 0) { throw "No native engine selected: $Engine" }

$mode = if ($ColdCli) { "cold-cli" } else { "resident" }
if ($GpuChunk) { $mode += "-gpu-chunk" }
$sourceStages = Join-Path $runRoot "sources"
$buildRoot = Join-Path $runRoot "build"
$builtArtifacts = @()

foreach ($spec in $selectedSpecs) {
    $baseSource = Join-Path $sourceStages ("base\" + $spec.Directory)
    $null = New-Item -ItemType Directory -Force -Path $baseSource
    & $PythonLauncher @pythonArgs $patchScript --source-root $extractedSource --destination $baseSource --variant $spec.Variant --helper-header $resizeHeader --status-header $statusHeader --file-output-header $fileOutputHeader --fast-png-header $fastPngHeader --cmake-template $templatePath
    if ($LASTEXITCODE -ne 0) { throw "Base source patch failed for $($spec.Variant)" }

    $selectedSource = $baseSource
    if (-not $ColdCli) {
        $residentSource = Join-Path $sourceStages ("resident\" + $spec.Directory)
        & $PythonLauncher @pythonArgs $residentScript --source-dir $baseSource --destination $residentSource --variant $spec.Variant
        if ($LASTEXITCODE -ne 0) { throw "Resident-session patch failed for $($spec.Variant)" }
        $selectedSource = $residentSource
    }

    if ($GpuChunk) {
        $gpuSource = Join-Path $sourceStages ("gpu-chunk\" + $spec.Directory)
        $null = New-Item -ItemType Directory -Force -Path $gpuSource
        Get-ChildItem -LiteralPath $selectedSource -Force | Copy-Item -Destination $gpuSource -Recurse -Force

        $gpuOutput = Join-Path $gpuSource (".manyue_gpu_chunk." + $spec.EngineSource)
        & $PythonLauncher @pythonArgs $gpuChunkScript --engine $spec.Variant --source (Join-Path $selectedSource $spec.EngineSource) --output $gpuOutput
        if ($LASTEXITCODE -ne 0) { throw "GPU chunk source patch failed for $($spec.Variant)" }
        Move-Item -LiteralPath $gpuOutput -Destination (Join-Path $gpuSource $spec.EngineSource) -Force
        $selectedSource = $gpuSource
    }

    $buildDir = Join-Path $buildRoot ($spec.Directory + "\" + $mode)
    $configureArgs = @(
        "-S", $selectedSource,
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
    $builtArtifacts += [pscustomobject]@{ Name = $spec.Output; Path = $destination }
}

Write-Output "source mode=$mode engine=$Engine run=$runRoot"
if ($GpuChunk) {
    Write-Output "MANYUE_GPU_CHUNK support is compiled in but remains off unless the worker environment sets it to 2 or 4."
}
Write-Output "No app assets, hashes, packaging scripts, Gradle files, or devices were changed by this helper."

$readelf = Join-Path $ndkRootPath "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (Test-Path -LiteralPath $readelf) {
    foreach ($artifact in $builtArtifacts) {
        Write-Output "--- $($artifact.Name) ELF header ---"
        & $readelf -h $artifact.Path | Select-String "Class:|Machine:"
        Write-Output "--- $($artifact.Name) dependencies ---"
        & $readelf -d $artifact.Path | Select-String "NEEDED"
    }
}
