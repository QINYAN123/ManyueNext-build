[CmdletBinding()]
param(
    [string]$ZigExe,
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"
$helperDir = $PSScriptRoot
$repoRoot = Split-Path -Parent (Split-Path -Parent $helperDir)
$workspaceWork = Split-Path -Parent $repoRoot
if (-not $ZigExe) {
    $ZigExe = Join-Path $workspaceWork "toolchains\native-build-tools\ziglang\zig.exe"
}
if (-not (Test-Path -LiteralPath $ZigExe)) {
    throw "Zig C++ driver not found: $ZigExe"
}
if (-not $OutputDir) {
    $OutputDir = Join-Path $workspaceWork "native-output-phase1\host-tests"
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$null = New-Item -ItemType Directory -Force -Path $OutputDir

$resizeExe = Join-Path $OutputDir "output-resize-test.exe"
$statusExe = Join-Path $OutputDir "ncnn-status-test.exe"
& $ZigExe c++ -std=c++11 -O2 -Wall -Wextra (Join-Path $helperDir "output_resize_test.cpp") -o $resizeExe
if ($LASTEXITCODE -ne 0) { throw "Could not compile output resize test" }
& $ZigExe c++ -std=c++11 -O2 -Wall -Wextra (Join-Path $helperDir "ncnn_status_test.cpp") -o $statusExe
if ($LASTEXITCODE -ne 0) { throw "Could not compile ncnn status test" }

$resizeOutput = & $resizeExe 2>&1
$resizeExit = $LASTEXITCODE
if ($resizeExit -ne 0 -or ($resizeOutput -join "`n") -notmatch "output resize tests passed") {
    throw "Resize test failed (exit=$resizeExit): $($resizeOutput -join ' ')"
}
Write-Output ($resizeOutput -join "`n")

$successOutput = & $statusExe 2>&1
$successExit = $LASTEXITCODE
if ($successExit -ne 0 -or ($successOutput -join "`n") -notmatch "ENCODE_REACHED") {
    throw "Zero-status process did not reach encoding (exit=$successExit): $($successOutput -join ' ')"
}

$failureOutput = & $statusExe --fail 2>&1
$failureExit = $LASTEXITCODE
if ($failureExit -eq 0 -or ($failureOutput -join "`n") -match "ENCODE_REACHED") {
    throw "Nonzero-status process reached encoding or returned success (exit=$failureExit): $($failureOutput -join ' ')"
}
Write-Output "ncnn status fail-fast test passed (success exits 0; mocked failure exits $failureExit without ENCODE_REACHED)"
exit 0
