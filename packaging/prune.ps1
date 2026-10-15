<#
.SYNOPSIS
  Keeps only the newest GitHub release and cleans local build artifacts.

.DESCRIPTION
  Deletes every release except the newest $Keep (removing its tag too), prunes
  stale local tags, and removes regenerable build output so a repeatedly built
  Rust/Gradle workspace does not fill the disk.

.EXAMPLE
  pwsh -File packaging/prune.ps1
  pwsh -File packaging/prune.ps1 -Keep 2 -NoLocal
#>
[CmdletBinding()]
param(
    [int]$Keep = 1,
    [switch]$NoLocal
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host "== GitHub releases (keeping newest $Keep) =="
$releases = gh release list --limit 100 --json tagName,createdAt | ConvertFrom-Json
if (-not $releases) {
    Write-Host "no releases found"
} else {
    $sorted = $releases | Sort-Object { [datetime]$_.createdAt } -Descending
    $keepList = ($sorted | Select-Object -First $Keep | ForEach-Object { $_.tagName }) -join ', '
    Write-Host "keeping: $keepList"
    foreach ($release in ($sorted | Select-Object -Skip $Keep)) {
        Write-Host "deleting release $($release.tagName)"
        gh release delete $release.tagName --yes --cleanup-tag
    }
    git fetch --prune --prune-tags origin | Out-Null
    Write-Host "local tags now: $((git tag) -join ', ')"
}

if ($NoLocal) {
    Write-Host "== local cleanup skipped (-NoLocal) =="
    return
}

Write-Host "== local build artifacts =="
$targets = @('agent-rs\target', 'dist', 'build\pyinstaller')
foreach ($relative in $targets) {
    $path = Join-Path $root $relative
    if (Test-Path -LiteralPath $path) {
        $size = (Get-ChildItem -LiteralPath $path -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
        Remove-Item -LiteralPath $path -Recurse -Force
        Write-Host ("removed {0} ({1:N1} MB)" -f $relative, ($size / 1MB))
    }
}

Write-Host "== temp junk =="
$temp = Join-Path $env:LOCALAPPDATA 'Temp'
$tempTargets = @(Get-ChildItem -LiteralPath $temp -Directory -Filter '_MEI*' -ErrorAction SilentlyContinue)
foreach ($name in @('vscode-stable-user-x64', 'Diagnostics', 'lwjgl_comma')) {
    $p = Join-Path $temp $name
    if (Test-Path -LiteralPath $p) { $tempTargets += Get-Item -LiteralPath $p }
}
foreach ($t in $tempTargets) {
    try {
        Remove-Item -LiteralPath $t.FullName -Recurse -Force -ErrorAction Stop
        Write-Host "removed temp\$($t.Name)"
    } catch {
        Write-Host "skipped temp\$($t.Name) (in use or access denied)"
    }
}

$zigZip = Join-Path $env:TEMP 'opencode\zig\zig.zip'
if (Test-Path -LiteralPath $zigZip) {
    Remove-Item -LiteralPath $zigZip -Force
    Write-Host "removed zig.zip (extracted zig kept for cross-compiles)"
}

Write-Host "done"
