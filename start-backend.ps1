$ErrorActionPreference = 'Stop'

$backendDirectory = Join-Path $PSScriptRoot 'backend'
Set-Location -LiteralPath $backendDirectory
Write-Host 'Starting Spring Boot: http://127.0.0.1:8080'
mvn spring-boot:run
