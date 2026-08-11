$ErrorActionPreference = 'Stop'

$quantDirectory = Join-Path $PSScriptRoot 'quant-service'
$pythonExecutable = Join-Path $quantDirectory '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $pythonExecutable)) {
    throw 'quant-service\.venv is missing. Install Python dependencies first.'
}

Set-Location -LiteralPath $quantDirectory
Write-Host 'Starting Python Quant/QMT service: http://127.0.0.1:8000'
Write-Host 'Do not use --reload or run multiple instances in QMT mode.'
& $pythonExecutable -m uvicorn app.main:app --host 127.0.0.1 --port 8000
