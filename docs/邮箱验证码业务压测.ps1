param(
    [int]$RequestCount = 1000,
    [string]$Endpoint = "http://localhost:9090/api/v1/auth/email-code"
)

# 兼容 Windows PowerShell 5.1。压测前必须设置 MAIL_ENABLED=false，避免真实发送邮件。
$results = New-Object System.Collections.Generic.List[object]
$overallWatch = [System.Diagnostics.Stopwatch]::StartNew()

for ($index = 1; $index -le $RequestCount; $index++) {
    $body = @{ email = "loadtest$index@example.com" } | ConvertTo-Json -Compress
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        Invoke-RestMethod -Uri $Endpoint -Method POST -ContentType "application/json" -Body $body | Out-Null
        $status = "SUCCESS"
    } catch {
        $status = "FAILED"
    }
    $watch.Stop()
    $results.Add([pscustomobject]@{ Status = $status; ElapsedMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2) })
}

$overallWatch.Stop()
$sorted = @($results | Sort-Object ElapsedMs)
$p95Index = [math]::Max(0, [math]::Ceiling($sorted.Count * 0.95) - 1)
$success = @($results | Where-Object { $_.Status -eq "SUCCESS" }).Count
$average = ($results | Measure-Object -Property ElapsedMs -Average).Average
$qps = $results.Count / $overallWatch.Elapsed.TotalSeconds

$report = @"
# 邮箱验证码业务压测报告

- 压测接口：$Endpoint
- 总请求数：$($results.Count)
- 执行模式：Windows PowerShell 5.1 顺序请求
- 成功数：$success
- 失败数：$($results.Count - $success)
- 成功率：$([math]::Round($success * 100 / $results.Count, 2))%
- 平均响应时间：$([math]::Round($average, 2)) ms
- P95 响应时间：$($sorted[$p95Index].ElapsedMs) ms
- 实际 QPS：$([math]::Round($qps, 2))
- 说明：本次压测必须在 MAIL_ENABLED=false 下执行，不会发送真实邮件。
- 限制：当前脚本为顺序压测，不代表高并发场景性能。
"@

$reportPath = Join-Path $PSScriptRoot "邮箱验证码业务压测报告.md"
$report | Set-Content -Path $reportPath -Encoding UTF8
$report
Write-Host "报告已生成：$reportPath"
