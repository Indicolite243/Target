$ErrorActionPreference = 'Stop'

# IDEA 的 Batch 控制台不会解析 ANSI 颜色控制符，关闭彩色输出避免日志显示为 [32m 等字符。
$env:NO_COLOR = '1'
$env:FORCE_COLOR = '0'

$frontendDirectory = Join-Path $PSScriptRoot 'frontend'
Set-Location -LiteralPath $frontendDirectory
Write-Host 'Starting Vue: http://127.0.0.1:5173'
npm run dev -- --host 127.0.0.1 --clearScreen false
