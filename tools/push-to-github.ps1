# Push the phone-lab repository to GitHub in one step.
#
# Usage (from anywhere):
#   powershell -File tools/push-to-github.ps1 -RepoUrl https://github.com/<you>/phone-lab.git \
#       -AuthorName "<git name>" -AuthorEmail "<git email>"
#
# With a fine-grained PAT (Contents: Read and write):
#   powershell -File tools/push-to-github.ps1 -RepoUrl https://github.com/<you>/phone-lab.git \
#       -AuthorName "<git name>" -AuthorEmail "<git email>" -Token "<github_pat_...>"
#
# The token is only used for the push URL and is never written to .git/config.
param(
    [Parameter(Mandatory = $true)][string]$RepoUrl,
    [string]$AuthorName,
    [string]$AuthorEmail,
    [string]$Token,
    [string]$Branch = "main",
    [switch]$SkipAuthorRewrite
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($AuthorName -and $AuthorEmail -and -not $SkipAuthorRewrite) {
    Write-Host "Rewriting author/committer of all commits to $AuthorName <$AuthorEmail> ..."
    $env:FILTER_BRANCH_SQUELCH_WARNING = "1"
    $filter = "export GIT_AUTHOR_NAME='$AuthorName'; export GIT_AUTHOR_EMAIL='$AuthorEmail'; " +
              "export GIT_COMMITTER_NAME='$AuthorName'; export GIT_COMMITTER_EMAIL='$AuthorEmail'"
    git filter-branch -f --env-filter $filter -- --all | Out-Null
    git config user.name "$AuthorName"
    git config user.email "$AuthorEmail"
}

$existing = git remote get-url origin 2>$null
if ($existing) {
    git remote set-url origin $RepoUrl
} else {
    git remote add origin $RepoUrl
}
Write-Host "Remote origin -> $RepoUrl"

if ($Token) {
    $pushUrl = $RepoUrl -replace '^https://', "https://x-access-token:$Token@"
    git push $pushUrl "HEAD:$Branch"
} else {
    git push -u origin $Branch
}
if ($LASTEXITCODE -ne 0) { Write-Error "git push failed"; exit $LASTEXITCODE }

Write-Host ""
Write-Host "Pushed. Next:"
Write-Host "  1) open $RepoUrl and check the README renders"
Write-Host "  2) add repository topics (android, kotlin, jetpack-compose, signal-processing, fft, wifi)"
Write-Host "  3) (optional) enable GitHub Actions under the Actions tab"
