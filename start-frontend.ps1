$ErrorActionPreference = 'Stop'

$frontendDirectory = Join-Path $PSScriptRoot 'frontend'
Set-Location -LiteralPath $frontendDirectory
Write-Host 'Starting Vue: http://127.0.0.1:5173'
npm run dev -- --host 127.0.0.1
