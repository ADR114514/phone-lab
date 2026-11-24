# Split the monorepo into three standalone repositories (optional alternative to the
# single-repo delivery). Each split keeps the full commit history of its subtree.
#
# Usage: powershell -File tools/split-repos.ps1 -OutDir E:\phone-function-repos
param(
    [Parameter(Mandatory = $true)][string]$OutDir
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$targets = @(
    @{ Name = "phone-lab-powertrace"; Prefix = "apps/powertrace" },
    @{ Name = "phone-lab-chargeaudit"; Prefix = "apps/chargeaudit" },
    @{ Name = "phone-lab-vibrodiag";  Prefix = "apps/vibrodiag"  }
)

foreach ($t in $targets) {
    Write-Host "Splitting $($t.Prefix) -> $($t.Name) ..."
    git branch -D "split-$($t.Name)" 2>$null | Out-Null
    git subtree split -P $t.Prefix -b "split-$($t.Name)" | Out-Null
    $dest = Join-Path $OutDir $t.Name
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    git init -q $dest
    git -C $dest fetch "$((Get-Location).Path)" "split-$($t.Name)" | Out-Null
    git -C $dest checkout -q -B main FETCH_HEAD
    Write-Host "  -> $dest"
}

Write-Host ""
Write-Host "Split repositories created under $OutDir."
Write-Host "Note: core/ is a local project dependency; for standalone repos also run"
Write-Host "      'git subtree split -P core -b core' and publish it, or copy core/ into each repo."
