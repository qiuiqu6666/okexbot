param([string]$EnvFile = (Join-Path $PSScriptRoot '../.env'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $EnvFile)) { throw 'Copy .env.example to .env and configure it first.' }
foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
    if ($line -match '^\s*(#|$)') { continue }
    $parts = $line.Split('=', 2)
    if ($parts.Length -ne 2 -or $parts[0].Trim() -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid environment file line.' }
    [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
}
Push-Location (Join-Path $PSScriptRoot '../backend')
try { & mvn spring-boot:run; if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed.' } }
finally { Pop-Location }
