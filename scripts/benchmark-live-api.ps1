[CmdletBinding()]
param(
    [ValidateRange(1, 30)]
    [int]$Iterations = 10,
    [ValidateRange(0, 10000)]
    [int]$IntervalMs = 2000
)

$ErrorActionPreference = 'Stop'

function Read-EnvironmentFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw ".env not found: $Path" }
    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') {
            $values[$matches[1].Trim()] = $matches[2].Trim().Trim('"')
        }
    }
    return $values
}

function Get-Percentile([double[]]$Values, [double]$Percentile) {
    if ($Values.Count -eq 0) { return $null }
    $ordered = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Min($ordered.Count - 1,
            [Math]::Ceiling($Percentile * $ordered.Count) - 1))
    return [Math]::Round([double]$ordered[$index], 2)
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$settings = Read-EnvironmentFile (Join-Path $projectRoot '.env')
$mysql = Get-Command mysql.exe -ErrorAction Stop
$mysqlUser = if ($settings.ContainsKey('MYSQL_USERNAME')) { $settings['MYSQL_USERNAME'] } else { 'root' }
$mysqlPassword = if ($settings.ContainsKey('MYSQL_PASSWORD')) { $settings['MYSQL_PASSWORD'] } else { '' }

# The account number is consumed only to call the local internal service; it is never written to output.
$accountRow = @(& $mysql.Source '--host=127.0.0.1' '--port=3306' "--user=$mysqlUser" "--password=$mysqlPassword" `
        '--database=stock_manager' '--batch' '--skip-column-names' `
        '--execute=SELECT id, account_no, environment FROM account WHERE broker = ''GUOJIN_QMT'' AND status <> ''DISABLED'' LIMIT 1;' 2>$null) |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($accountRow)) { throw 'No active GUOJIN_QMT account was found in MySQL.' }
$accountCells = $accountRow -split "`t"
if ($accountCells.Count -lt 3) { throw 'Unexpected account lookup format.' }

$headers = @{
    'X-Internal-Token' = $settings['QUANT_INTERNAL_TOKEN']
    'X-Trace-Id' = 'live-benchmark'
}
$body = @{
    accountId = $accountCells[0]
    externalAccountId = $accountCells[1]
    environment = $accountCells[2]
    includePositions = $true
    includeOrders = $false
} | ConvertTo-Json -Compress

$samples = [System.Collections.Generic.List[object]]::new()
for ($index = 1; $index -le $Iterations; $index++) {
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-RestMethod 'http://127.0.0.1:8000/internal/v1/accounts/live' -Method Post `
            -ContentType 'application/json' -Headers $headers -Body $body -TimeoutSec 10
        $timer.Stop()
        $samples.Add([PSCustomObject]@{
                HttpMs = [double]$timer.ElapsedMilliseconds
                ServiceMs = [double]$response.durationMs
                Source = [string]$response.data.source
                Positions = [int]$response.data.positions.Count
                Warnings = [int]$response.data.warnings.Count
            })
    } catch {
        $timer.Stop()
        $samples.Add([PSCustomObject]@{
                HttpMs = [double]$timer.ElapsedMilliseconds
                ServiceMs = $null
                Source = 'ERROR'
                Positions = 0
                Warnings = 1
            })
        Write-Warning "Sample $index failed: $($_.Exception.Message)"
    }
    if ($index -lt $Iterations -and $IntervalMs -gt 0) { Start-Sleep -Milliseconds $IntervalMs }
}

$successful = @($samples | Where-Object { $_.Source -ne 'ERROR' })
$httpMs = [double[]]@($successful | ForEach-Object { $_.HttpMs })
$serviceMs = [double[]]@($successful | ForEach-Object { $_.ServiceMs })
[PSCustomObject]@{
    Iterations = $Iterations
    SuccessfulSamples = $successful.Count
    FailedSamples = $Iterations - $successful.Count
    IntervalMs = $IntervalMs
    Sources = ($successful.Source | Sort-Object -Unique) -join ','
    PositionsPerSnapshot = if ($successful.Count) { ($successful[0].Positions) } else { $null }
    HttpP50Ms = Get-Percentile $httpMs 0.50
    HttpP95Ms = Get-Percentile $httpMs 0.95
    ServiceP50Ms = Get-Percentile $serviceMs 0.50
    ServiceP95Ms = Get-Percentile $serviceMs 0.95
    WarningSamples = @($successful | Where-Object { $_.Warnings -gt 0 }).Count
} | Format-List
