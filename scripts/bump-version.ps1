# PowerShell script to bump version, tag and push
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Version,

    [switch]$NoPush,
    [switch]$NoTag
)

$ErrorActionPreference = "Stop"

$scriptDir = $PSScriptRoot
$pyScript = Join-Path $scriptDir "bump-version.py"

$pythonCmd = Get-Command python3 -ErrorAction SilentlyContinue
if (-not $pythonCmd) {
    $pythonCmd = Get-Command python -ErrorAction SilentlyContinue
}

if (-not $pythonCmd) {
    Write-Error "❌ Error: Python 3 is required to run bump-version.py."
    exit 1
}

$pyArgs = @($pyScript, $Version)
if ($NoPush) { $pyArgs += "--no-push" }
if ($NoTag) { $pyArgs += "--no-tag" }

& $pythonCmd.Source $pyArgs
