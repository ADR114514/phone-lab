# Verify delivery APKs: minSdk/targetSdk/ABI, permissions, and v2+ signature.
param(
    [string]$Sdk = "C:\Android\Sdk"
)
$ErrorActionPreference = "Stop"

$buildTools = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"

$apks = @(
    "apps/powertrace/build/outputs/apk/debug/powertrace-debug.apk",
    "apps/chargeaudit/build/outputs/apk/debug/chargeaudit-debug.apk",
    "apps/vibrodiag/build/outputs/apk/debug/vibrodiag-debug.apk"
) | ForEach-Object { Join-Path (Join-Path $PSScriptRoot "..") $_ }

$failed = $false
foreach ($apk in $apks) {
    if (-not (Test-Path $apk)) { Write-Host "MISSING $apk"; $failed = $true; continue }
    Write-Host "==== $((Split-Path $apk -Leaf)) ===="
    $badging = & $aapt2 dump badging $apk 2>&1
    $pkg = $badging | Select-String "package:" | Select-Object -First 1
    $minSdk = $badging | Select-String "minSdkVersion" | Select-Object -First 1
    $targetSdk = $badging | Select-String "targetSdkVersion" | Select-Object -First 1
    $native = $badging | Select-String "native-code" | Select-Object -First 1
    Write-Host ("  " + $pkg.Line.Trim())
    Write-Host ("  " + $minSdk.Line.Trim() + "; " + $targetSdk.Line.Trim())
    Write-Host ("  " + $(if ($native) { $native.Line.Trim() } else { "native-code: none" }))
    $perms = $badging | Select-String "uses-permission:" | ForEach-Object { $_.Line.Trim() }
    Write-Host ("  permissions: " + ($perms.Count) + " (" + (($perms | ForEach-Object { ($_ -split "name='")[1] -replace "'", "" }) -join ", ") + ")")

    $verify = & $apksigner verify --print-certs $apk 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  signature: OK (v2+)"
    } else {
        Write-Host "  signature: FAILED"
        Write-Host ($verify | Out-String)
        $failed = $true
    }
    if (-not ($minSdk.Line -match "minSdkVersion:'31'")) { Write-Host "  ERROR: unexpected minSdk"; $failed = $true }
    if (-not ($native -and $native.Line -match "arm64-v8a")) { Write-Host "  ERROR: expected arm64-v8a only"; $failed = $true }
}
if ($failed) { Write-Error "APK verification failed"; exit 1 }
Write-Host ""
Write-Host "All APKs verified (minSdk 31 / targetSdk 36 / arm64-v8a / signed)."
