# Verify minimum SDK, ABI and exported permissions of the debug APKs.
param(
    [string]$Sdk = "C:\Android\Sdk"
)
$aapt2 = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory |
    Sort-Object Name -Descending | Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName "aapt2.exe" }

$apks = Get-ChildItem (Join-Path $PSScriptRoot "..\apps") -Recurse -Filter "*-debug.apk"
if (-not $apks) { Write-Error "No debug APKs found. Build first."; exit 1 }

foreach ($apk in $apks) {
    Write-Host "==== $($apk.Name) ===="
    & $aapt2 dump badging $apk.FullName |
        Select-String -Pattern "package:|sdkVersion|targetSdkVersion|native-code|uses-permission" |
        ForEach-Object { $_.Line }
}
