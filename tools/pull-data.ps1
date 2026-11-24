# Pull experiment data from a connected debug device into data/raw/<app>/
# Usage: powershell -File tools/pull-data.ps1 -Package com.mobilelab.powertrace -AppTag power
param(
    [Parameter(Mandatory = $true)][string]$Package,
    [Parameter(Mandatory = $true)][ValidateSet('power', 'topic07', 'vibro')][string]$AppTag,
    [string]$Adb = "adb"
)

$dest = Join-Path $PSScriptRoot ("..\data\raw\" + $AppTag)
New-Item -ItemType Directory -Path $dest -Force | Out-Null
Write-Host "Pulling /data/data/$Package/files/experiments from device ..."
# App private dirs are readable through run-as on debuggable builds.
& $Adb exec-out "run-as $Package tar cf - files/experiments" | tar xf - -C $dest
if ($LASTEXITCODE -ne 0) {
    Write-Error "adb pull failed. Ensure the device is connected and the app is a debuggable build."
    exit 1
}
Write-Host "Done -> $dest"
