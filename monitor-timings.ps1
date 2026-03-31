# monitor-timings.ps1 — Reports pipeline timing metrics from Spring Boot Actuator.
#
# Instrumentation in KafkaConsumerListener records two Micrometer timers:
#   kafka.processor.e2e.latency      — receive → ack (includes the scheduled delay)
#   kafka.processor.pipeline.latency — worker execution only (milliseconds)
#
# And four counters (tagged by eventType or reason):
#   kafka.processor.messages.received
#   kafka.processor.messages.published
#   kafka.processor.messages.siphoned
#   kafka.processor.messages.failed
#
# Usage:
#   .\monitor-timings.ps1                    single snapshot
#   .\monitor-timings.ps1 -Watch             refresh every 5 seconds
#   .\monitor-timings.ps1 -Watch -Interval 10
#   .\monitor-timings.ps1 -BaseUrl http://localhost:8080
#
# For P95/percentile histograms, open Grafana at http://localhost:3000 and query:
#   histogram_quantile(0.95, rate(kafka_processor_e2e_latency_seconds_bucket[1m]))

param(
    [string]$BaseUrl  = "http://localhost:8080",
    [switch]$Watch,
    [int]$Interval    = 5
)

function Get-Metric {
    param([string]$BaseUrl, [string]$Name, [string]$Tag = "")
    $url = "$BaseUrl/actuator/metrics/$Name"
    if ($Tag) { $url += "?tag=$Tag" }
    try {
        return Invoke-RestMethod -Uri $url -ErrorAction Stop
    } catch {
        return $null
    }
}

function Get-CounterTotal {
    param([object]$Metric)
    if (-not $Metric) { return 0 }
    $m = $Metric.measurements | Where-Object { $_.statistic -eq "COUNT" } | Select-Object -First 1
    if ($m) { return [int]$m.value }
    $m = $Metric.measurements | Where-Object { $_.statistic -eq "VALUE" } | Select-Object -First 1
    if ($m) { return [int]$m.value }
    return 0
}

function Format-Seconds { param([double]$s) return ("{0:F2}s" -f $s) }

function Get-Stats {
    param([string]$BaseUrl)

    # Fetch all metrics in parallel using jobs
    $e2e      = Get-Metric $BaseUrl "kafka.processor.e2e.latency"
    $pipeline = Get-Metric $BaseUrl "kafka.processor.pipeline.latency"
    $received = Get-Metric $BaseUrl "kafka.processor.messages.received"
    $published= Get-Metric $BaseUrl "kafka.processor.messages.published"
    $siphoned = Get-Metric $BaseUrl "kafka.processor.messages.siphoned"
    $failed   = Get-Metric $BaseUrl "kafka.processor.messages.failed"

    Clear-Host
    Write-Host "══════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  Kafka Processor — Pipeline Timing Monitor" -ForegroundColor Cyan
    Write-Host "  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   base: $BaseUrl" -ForegroundColor DarkCyan
    Write-Host "══════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host ""

    if (-not $e2e) {
        Write-Host "ERROR: Could not reach $BaseUrl/actuator/metrics" -ForegroundColor Red
        Write-Host "  Is the app running? Is management.endpoints.web.exposure.include set?" -ForegroundColor DarkRed
        return
    }

    # ── Counters ─────────────────────────────────────────────────────────
    $rcvCount = Get-CounterTotal $received
    $pubCount = Get-CounterTotal $published
    $sphCount = Get-CounterTotal $siphoned
    $fldCount = Get-CounterTotal $failed

    Write-Host "Message Counts" -ForegroundColor Yellow
    Write-Host ("  Received   (entered pipeline) : {0}" -f $rcvCount)
    Write-Host ("  Published  (full success)     : {0}" -f $pubCount)
    Write-Host ("  Siphoned   (fast-path)        : {0}" -f $sphCount)
    Write-Host ("  Failed     (dead-lettered)    : {0}" -f $fldCount)
    if ($rcvCount -gt 0) {
        $inFlight = $rcvCount - $pubCount - $fldCount
        Write-Host ("  In-flight  (est.)             : {0}" -f [math]::Max(0, $inFlight))
    }
    Write-Host ""

    # ── E2E Latency ───────────────────────────────────────────────────────
    $e2eCount = ($e2e.measurements | Where-Object { $_.statistic -eq "COUNT" }).value
    $e2eTotal = ($e2e.measurements | Where-Object { $_.statistic -eq "TOTAL_TIME" }).value
    $e2eMax   = ($e2e.measurements | Where-Object { $_.statistic -eq "MAX" }).value

    Write-Host "E2E Latency  (receive → ack, includes scheduled delay)  [$([int]$e2eCount) msgs]" -ForegroundColor Yellow
    if ($e2eCount -gt 0) {
        Write-Host ("  Avg : {0}" -f (Format-Seconds ($e2eTotal / $e2eCount)))
        Write-Host ("  Max : {0}" -f (Format-Seconds $e2eMax))

        # Client-side percentiles (published by app if management.metrics.distribution.percentiles is set)
        $p50 = Get-Metric $BaseUrl "kafka.processor.e2e.latency" "quantile:0.5"
        $p95 = Get-Metric $BaseUrl "kafka.processor.e2e.latency" "quantile:0.95"
        $p99 = Get-Metric $BaseUrl "kafka.processor.e2e.latency" "quantile:0.99"
        if ($p50) { Write-Host ("  P50 : {0}" -f (Format-Seconds ($p50.measurements[0].value))) }
        if ($p95) { Write-Host ("  P95 : {0}" -f (Format-Seconds ($p95.measurements[0].value))) }
        if ($p99) { Write-Host ("  P99 : {0}" -f (Format-Seconds ($p99.measurements[0].value))) }
    } else {
        Write-Host "  No completed messages yet" -ForegroundColor DarkGray
    }
    Write-Host ""

    # ── Pipeline Latency (worker execution only) ──────────────────────────
    $plCount = ($pipeline.measurements | Where-Object { $_.statistic -eq "COUNT" }).value
    $plTotal = ($pipeline.measurements | Where-Object { $_.statistic -eq "TOTAL_TIME" }).value
    $plMax   = ($pipeline.measurements | Where-Object { $_.statistic -eq "MAX" }).value

    Write-Host "Pipeline Latency  (worker execution only, excl. delay)  [$([int]$plCount) msgs]" -ForegroundColor Yellow
    if ($plCount -gt 0) {
        Write-Host ("  Avg : {0}" -f (Format-Seconds ($plTotal / $plCount)))
        Write-Host ("  Max : {0}" -f (Format-Seconds $plMax))
        $pp95 = Get-Metric $BaseUrl "kafka.processor.pipeline.latency" "quantile:0.95"
        if ($pp95) { Write-Host ("  P95 : {0}" -f (Format-Seconds ($pp95.measurements[0].value))) }
    } else {
        Write-Host "  No completed messages yet" -ForegroundColor DarkGray
    }
    Write-Host ""

    # ── Failure breakdown by reason ───────────────────────────────────────
    if ($failed -and $failed.availableTags) {
        $reasonTag = $failed.availableTags | Where-Object { $_.tag -eq "reason" }
        if ($reasonTag -and $reasonTag.values.Count -gt 0) {
            Write-Host "Failure Breakdown" -ForegroundColor Red
            foreach ($reason in $reasonTag.values | Sort-Object) {
                $detail = Get-Metric $BaseUrl "kafka.processor.messages.failed" "reason:$reason"
                $cnt = Get-CounterTotal $detail
                Write-Host ("  {0,-35} {1}" -f $reason, $cnt)
            }
            Write-Host ""
        }
    }

    Write-Host "For P95 histograms open Grafana: http://localhost:3000" -ForegroundColor DarkGray
    Write-Host "  histogram_quantile(0.95, rate(kafka_processor_e2e_latency_seconds_bucket[1m]))" -ForegroundColor DarkGray
    if ($Watch) {
        Write-Host ""
        Write-Host "Refreshing every ${Interval}s — Ctrl+C to stop" -ForegroundColor DarkGray
    }
}

if ($Watch) {
    while ($true) {
        Get-Stats -BaseUrl $BaseUrl
        Start-Sleep -Seconds $Interval
    }
} else {
    Get-Stats -BaseUrl $BaseUrl
}
