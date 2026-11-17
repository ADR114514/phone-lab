# One-command publish: verify token -> create repo if missing -> rewrite commit
# authors -> push -> print repository URL. The token is never written to disk.
#
# Usage:
#   powershell -File tools/publish-github.ps1 -Token "<github_pat_...>" -RepoName phone-lab
#   powershell -File tools/publish-github.ps1 -Token "..." -RepoName phone-lab -Private
#   powershell -File tools/publish-github.ps1 -Token "..." -DryRun     # check token identity only
#
# Token scopes:
#   * classic PAT: repo (create repos + push)
#   * fine-grained: Contents = Read and write, Administration = Read and write
#     (needed to create the repository; skip creation by creating it yourself)
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$RepoName = "phone-lab",
    [string]$AuthorName,
    [string]$AuthorEmail,
    [switch]$Private,
    [switch]$DryRun,
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"
$api = "https://api.github.com"
$headers = @{
    Authorization          = "Bearer $Token"
    "User-Agent"           = "phone-lab-publisher"
    "Accept"               = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}

Write-Host "[1/5] Verifying token ..."
try {
    $user = Invoke-RestMethod -Uri "$api/user" -Headers $headers -Method Get
} catch {
    Write-Error "Token rejected by GitHub: $($_.Exception.Message)"
    exit 1
}
$login = $user.login
Write-Host "      authenticated as: $login  ($($user.name))"

if (-not $AuthorName)  { $AuthorName = if ($user.name) { $user.name } else { $login } }
if (-not $AuthorEmail) { $AuthorEmail = "$login@users.noreply.github.com" }
Write-Host "      commit identity:  $AuthorName <$AuthorEmail>"

if ($DryRun) {
    Write-Host "DryRun: token is valid, stopping before any repository change."
    exit 0
}

Write-Host "[2/5] Ensuring repository $login/$RepoName exists ..."
$repoUrl = "https://github.com/$login/$RepoName.git"
$exists = $true
try {
    Invoke-RestMethod -Uri "$api/repos/$login/$RepoName" -Headers $headers -Method Get | Out-Null
    Write-Host "      repository already exists"
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -ne 404) { Write-Error "GitHub API error while checking repo: $($_.Exception.Message)"; exit 1 }
    $exists = $false
}
if (-not $exists) {
    $payload = @{ name = $RepoName; description = "PhoneLab: Android field instruments for power/thermal, Wi-Fi blind-zone and vibro-acoustic experiments"; private = [bool]$Private } | ConvertTo-Json
    Invoke-RestMethod -Uri "$api/user/repos" -Headers $headers -Method Post -Body $payload -ContentType "application/json" | Out-Null
    Write-Host "      repository created ($(if ($Private) { 'private' } else { 'public' }))"
}

Write-Host "[3/5] Rewriting commit authors to $AuthorName <$AuthorEmail> ..."
Set-Location (Join-Path $PSScriptRoot "..")
$env:FILTER_BRANCH_SQUELCH_WARNING = "1"
$filter = "export GIT_AUTHOR_NAME='$AuthorName'; export GIT_AUTHOR_EMAIL='$AuthorEmail'; " +
          "export GIT_COMMITTER_NAME='$AuthorName'; export GIT_COMMITTER_EMAIL='$AuthorEmail'"
git filter-branch -f --env-filter $filter -- --all | Out-Null
git config user.name "$AuthorName"
git config user.email "$AuthorEmail"

Write-Host "[4/5] Pushing $Branch to $repoUrl ..."
git remote remove origin 2>$null | Out-Null
git remote add origin $repoUrl
git -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("x-access-token:$Token")))" push -u origin $Branch
if ($LASTEXITCODE -ne 0) {
    # Fallback: temporary URL with embedded token; remove remote afterwards.
    $pushed = $false
    git push "https://x-access-token:$Token@github.com/$login/$RepoName.git" "HEAD:$Branch"
    if ($LASTEXITCODE -eq 0) { $pushed = $true }
    if (-not $pushed) { Write-Error "git push failed"; exit 1 }
}

Write-Host "[5/5] Done."
Write-Host ""
Write-Host "Repository: https://github.com/$login/$RepoName"
Write-Host "Make sure to revoke the token afterwards: https://github.com/settings/tokens"
