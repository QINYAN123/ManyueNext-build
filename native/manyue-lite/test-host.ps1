[CmdletBinding()]
param(
    [string]$ModelDirectory,
    [string]$GoldenRoot,
    [string]$SourcePng,
    [string]$OutputDir,
    [string]$BuildDir,
    [string]$WorkRoot,
    [string]$CMakeExe,
    [string]$NinjaExe,
    [string]$NcnnPrefix,
    [string]$WebpRoot
)

$ErrorActionPreference = 'Stop'
$sourceDir = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $sourceDir)
if (-not $WorkRoot) { $WorkRoot = Split-Path -Parent $repoRoot }
$WorkRoot = [System.IO.Path]::GetFullPath($WorkRoot)
if (-not $CMakeExe) { $CMakeExe = Join-Path $WorkRoot 'toolchains\native-build-tools\cmake\data\bin\cmake.exe' }
if (-not $NinjaExe) { $NinjaExe = Join-Path $WorkRoot 'toolchains\native-build-tools\bin\ninja.exe' }
if (-not $NcnnPrefix) { $NcnnPrefix = Join-Path $WorkRoot 'lite-host-toolchain\prefix' }
if (-not $WebpRoot) { $WebpRoot = Join-Path $WorkRoot 'native-deps\libwebp-source\libwebp-1.5.0' }
if (-not $BuildDir) { $BuildDir = Join-Path $WorkRoot 'lite-native-build\host-vulkan-override' }
if (-not $OutputDir) { $OutputDir = Join-Path $WorkRoot 'lite-native-build\host-test-output' }
if (-not $ModelDirectory) { $ModelDirectory = Join-Path $sourceDir 'training\golden\contract-random\model' }
if (-not $SourcePng) { $SourcePng = Join-Path $sourceDir 'training\golden\contract-random\fixture\source.png' }
if (-not $GoldenRoot) { $GoldenRoot = Join-Path $WorkRoot 'light-sr-training\golden\contract-random' }

foreach ($item in @($CMakeExe, $NinjaExe, $WebpRoot, $ModelDirectory, $SourcePng, $GoldenRoot,
                    (Join-Path $NcnnPrefix 'include\ncnn\net.h'),
                    (Join-Path $NcnnPrefix 'lib\libncnn.dll.a'),
                    (Join-Path $NcnnPrefix 'bin\libncnn.dll'))) {
    if (-not (Test-Path -LiteralPath $item)) { throw "Required host-test input missing: $item" }
}
$wrapperRoot = Join-Path $WorkRoot 'lite-host-toolchain\wrappers'
foreach ($wrapper in @('zig-cc.cmd', 'zig-cxx.cmd', 'zig-ar.cmd', 'zig-ranlib.cmd')) {
    if (-not (Test-Path -LiteralPath (Join-Path $wrapperRoot $wrapper))) {
        throw "Host Zig wrapper missing: $(Join-Path $wrapperRoot $wrapper)"
    }
}
$ModelDirectory = (Resolve-Path -LiteralPath $ModelDirectory).Path
$SourcePng = (Resolve-Path -LiteralPath $SourcePng).Path
$GoldenRoot = (Resolve-Path -LiteralPath $GoldenRoot).Path
$NcnnPrefix = (Resolve-Path -LiteralPath $NcnnPrefix).Path
$WebpRoot = (Resolve-Path -LiteralPath $WebpRoot).Path
$BuildDir = [System.IO.Path]::GetFullPath($BuildDir)
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$null = New-Item -ItemType Directory -Force -Path $BuildDir
$null = New-Item -ItemType Directory -Force -Path $OutputDir

$ncnnRuntime = Join-Path $NcnnPrefix 'bin\libncnn.dll'
$configureArgs = @(
    '-S', $sourceDir,
    '-B', $BuildDir,
    '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$NinjaExe",
    '-DCMAKE_BUILD_TYPE=Release',
    '-DMANYUE_LITE_BUILD_HOST_TESTS=ON',
    "-DMANYUE_LITE_NCNN_LIBRARY=$ncnnRuntime",
    "-DMANYUE_LITE_NCNN_IMPLIB=$(Join-Path $NcnnPrefix 'lib\libncnn.dll.a')",
    "-DMANYUE_LITE_NCNN_INCLUDE_DIR=$(Join-Path $NcnnPrefix 'include')",
    "-DMANYUE_LITE_WEBP_ROOT=$WebpRoot",
    "-DMANYUE_LITE_HOST_ARCHIVER=$(Join-Path $wrapperRoot 'zig-ar.cmd')",
    "-DMANYUE_LITE_HOST_RANLIB=$(Join-Path $wrapperRoot 'zig-ranlib.cmd')",
    "-DCMAKE_C_COMPILER=$(Join-Path $wrapperRoot 'zig-cc.cmd')",
    "-DCMAKE_CXX_COMPILER=$(Join-Path $wrapperRoot 'zig-cxx.cmd')"
)
& $CMakeExe @configureArgs
if ($LASTEXITCODE -ne 0) { throw 'Host Vulkan CMake configure failed' }
& $CMakeExe --build $BuildDir --target manyue_lite_reference_test manyue_lite_host_test --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'Host Vulkan golden runner build failed' }
$referenceExe = Join-Path $BuildDir 'manyue_lite_reference_test.exe'
$env:PATH = (Join-Path $NcnnPrefix 'bin') + ';' + $env:PATH
& $referenceExe
if ($LASTEXITCODE -ne 0) { throw 'Host reference coordinate/head tests failed' }

# The codec regression needs an actual JPEG with the same source dimensions.
Add-Type -AssemblyName System.Drawing
$jpegPath = Join-Path $OutputDir 'source.jpg'
$bitmap = [System.Drawing.Bitmap]::FromFile($SourcePng)
try {
    $jpegCodec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
        Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
    if (-not $jpegCodec) { throw 'System JPEG codec is unavailable' }
    $bitmap.Save($jpegPath, $jpegCodec, $null)
} finally {
    $bitmap.Dispose()
}

$testExe = Join-Path $BuildDir 'manyue_lite_host_test.exe'
$nativeOutput = & $testExe $ModelDirectory $SourcePng $jpegPath $GoldenRoot $OutputDir 2>&1
$testExit = $LASTEXITCODE
foreach ($line in $nativeOutput) { Write-Output $line }
if ($testExit -ne 0) { throw "Host Vulkan numerical/cancellation tests failed with exit $testExit" }
$resultLine = $nativeOutput | Where-Object { "$($_)".StartsWith('RESULT_JSON=') } | Select-Object -Last 1
if (-not $resultLine) { throw 'Host test exited successfully without RESULT_JSON' }
$resultJson = "$resultLine".Substring('RESULT_JSON='.Length)
$resultObject = $resultJson | ConvertFrom-Json
if (-not $resultObject.passed -or $resultObject.actualVulkanExecutions -lt 1 -or
    $resultObject.gpuBackend -ne 'vulkan') {
    throw 'Host runner did not prove a passing, actually executed Vulkan path'
}
$reportPath = Join-Path $OutputDir 'host-vulkan-result.json'
$resultObject | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Output "HOST_VULKAN_REPORT=$reportPath"
