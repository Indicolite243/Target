# Start the frontend and Python quant service. Spring Boot on port 8080 is not touched.

$ErrorActionPreference = "Stop"
$targetRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontendDir = Join-Path $targetRoot "frontend"
$quantDir = Join-Path $targetRoot "quant-service"
$pythonExe = Join-Path $quantDir ".venv\Scripts\python.exe"
$uvicornExe = Join-Path $quantDir ".venv\Scripts\uvicorn.exe"

function Get-ListeningProcess([int]$port) {
    Get-NetTCPConnection -LocalAddress "127.0.0.1" -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty OwningProcess
}

function Wait-ForPort([int]$port, [int]$seconds = 15) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Get-ListeningProcess $port) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

Write-Host "=== Stock Vision Analytics services ===" -ForegroundColor Cyan

if (-not (Test-Path -LiteralPath $pythonExe)) { throw "Missing Python: $pythonExe" }
if (-not (Test-Path -LiteralPath $uvicornExe)) { throw "Missing uvicorn: $uvicornExe" }
if (-not (Test-Path -LiteralPath (Join-Path $frontendDir "package.json"))) { throw "Missing frontend: $frontendDir" }
if (-not (Test-Path -LiteralPath (Join-Path $frontendDir "node_modules"))) { throw "Run npm install in frontend first" }

$quantPid = Get-ListeningProcess 8000
if ($quantPid) {
    Write-Host "Python service already running on 8000 (PID $quantPid)." -ForegroundColor Yellow
} else {
    Write-Host "Starting Python service on 8000..." -ForegroundColor Green
    Start-Process -FilePath $pythonExe -ArgumentList @($uvicornExe, "app.main:app", "--host", "127.0.0.1", "--port", "8000") -WorkingDirectory $quantDir -WindowStyle Normal
    $quantReady = Wait-ForPort 8000
    if ($quantReady) { Write-Host "Python service started." -ForegroundColor Green }
    else { Write-Warning "Python service did not listen on 8000 within 15 seconds." }
}

$frontendPid = Get-ListeningProcess 5173
if ($frontendPid) {
    Write-Host "Frontend already running on 5173 (PID $frontendPid)." -ForegroundColor Yellow
} else {
    Write-Host "Starting Vite frontend on 5173..." -ForegroundColor Green
    Start-Process -FilePath "npm.cmd" -ArgumentList @("run", "dev", "--", "--host", "127.0.0.1") -WorkingDirectory $frontendDir -WindowStyle Normal
    $frontendReady = Wait-ForPort 5173
    if ($frontendReady) { Write-Host "Frontend started." -ForegroundColor Green }
    else { Write-Warning "Frontend did not listen on 5173 within 15 seconds." }
}

Start-Sleep -Seconds 1
Start-Process "http://127.0.0.1:5173/login"
Write-Host "Done: frontend http://127.0.0.1:5173, Python http://127.0.0.1:8000" -ForegroundColor Cyan
