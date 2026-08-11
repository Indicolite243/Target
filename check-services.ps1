$ErrorActionPreference = 'Continue'

$services = @(
    @{ Name = 'Python Quant/QMT'; Url = 'http://127.0.0.1:8000/internal/v1/health' },
    @{ Name = 'Spring Boot'; Url = 'http://127.0.0.1:8080/api/v1/health' },
    @{ Name = 'Vue'; Url = 'http://127.0.0.1:5173/' }
)

foreach ($service in $services) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $service.Url -TimeoutSec 5
        Write-Host "[正常] $($service.Name) HTTP $($response.StatusCode)" -ForegroundColor Green
    } catch {
        Write-Host "[未就绪] $($service.Name)：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}
