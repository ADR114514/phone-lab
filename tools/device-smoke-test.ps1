# One-shot smoke test on a connected Android device (adb).
#
#   powershell -File tools/device-smoke-test.ps1
#   powershell -File tools/device-smoke-test.ps1 -SkipInstall -OutDir E:\device-test
#
# Steps: install 3 APKs -> launch each -> capture screenshot + crash buffer
#        -> pull exported experiment files. Requires USB debugging + authorization.
param(
    [string]$Adb = "C:\Android\Sdk\platform-tools\adb.exe",
    [string]$OutDir = "$PSScriptRoot\..\..\device-test",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..")
$apps = @(
    @{ Pkg = "com.mobilelab.powertrace"; Apk = "apps/powertrace/build/outputs/apk/debug/powertrace-debug.apk"; Tag = "powertrace" },
    @{ Pkg = "com.mobilelab.signalmap";  Apk = "apps/signalmap/build/outputs/apk/debug/signalmap-debug.apk";  Tag = "signalmap"  },
    @{ Pkg = "com.mobilelab.vibrodiag";  Apk = "apps/vibrodiag/build/outputs/apk/debug/vibrodiag-debug.apk";  Tag = "vibrodiag"  }
)

New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $OutDir "screenshots") -Force | Out-Null

Write-Host "[0/3] Checking device ..."
& $Adb wait-for-device
$devices = & $Adb devices
if ($devices -notmatch "\sdevice\s*$" -and $devices -notmatch "\sdevice\b") {
    Write-Error "No authorized device. Unlock the phone and accept the USB debugging prompt."
    exit 1
}
$wake = & $Adb shell dumpsys power | Select-String "mWakefulness="
Write-Host "      $($wake.Line.Trim())"

if (-not $SkipInstall) {
    Write-Host "[1/3] Installing APKs ..."
    foreach ($app in $apps) {
        $apk = Join-Path $repo $app.Apk
        if (-not (Test-Path $apk)) { Write-Warning "missing $apk"; continue }
        $out = & $Adb install -r -g $apk 2>&1
        Write-Host "      $($app.Tag): $($out | Select-Object -Last 1)"
    }
}

Write-Host "[2/3] Launching apps and collecting evidence ..."
foreach ($app in $apps) {
    & $Adb logcat -c | Out-Null
    & $Adb shell am start -W -n "$($app.Pkg)/.MainActivity" | Out-Null
    Start-Sleep -Seconds 3
    $top = (& $Adb shell dumpsys activity activities | Select-String "topResumedActivity" | Select-Object -First 1).Line
    $crash = (& $Adb logcat -d -b crash | Select-String "mobilelab")
    $shot = Join-Path $OutDir "screenshots/$($app.Tag)-home.png"
    & $Adb shell screencap -p /sdcard/_smoke.png | Out-Null
    & $Adb pull /sdcard/_smoke.png $shot | Out-Null
    $state = if ($crash) { "CRASH" } elseif ($top -match [regex]::Escape($app.Pkg)) { "OK" } else { "NOT-FOREGROUND" }
    Write-Host "      $($app.Tag): $state  -> $shot"
    if ($crash) { $crash | ForEach-Object { Write-Host "        $_" } }
}

Write-Host "[3/3] Pulling experiment data (external files dir) ..."
foreach ($app in $apps) {
    $target = Join-Path $OutDir ("data/" + $app.Tag)
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    $src = "/sdcard/Android/data/$($app.Pkg)/files/experiments"
    & $Adb pull $src $target 2>&1 | Select-Object -Last 1 | ForEach-Object { Write-Host "      $($app.Tag): $_" }
}

Write-Host ""
Write-Host "Smoke test finished. Evidence in: $OutDir"
Write-Host "Manual follow-up: grant permissions in-app, run one measurement/group, then re-run with -SkipInstall to pull data."
