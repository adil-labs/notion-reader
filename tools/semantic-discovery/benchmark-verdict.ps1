function Assert-ShippingBenchmarkReport {
    param([Parameter(Mandatory)]$Report)
    if ($Report.status -eq 'error') { throw "Benchmark error: $($Report.errorType)" }
    if ($Report.notes -ne 100 -or $Report.passages -ne 10000 -or $Report.samples -ne 20 -or $Report.querySamplesMs.Count -ne 20) {
        throw 'INCOMPLETE: benchmark cardinality does not match the measurement contract'
    }
    # Historical reports have no window fields. Any window metadata opts into the complete v2 contract.
    $windowFields = @('requestedQueryDelaySeconds', 'readyEpochMs', 'readyElapsedRealtimeMs',
        'queryWindowStartEpochMs', 'queryWindowEndEpochMs',
        'queryWindowStartElapsedRealtimeMs', 'queryWindowEndElapsedRealtimeMs')
    $hasWindow = $null -ne $Report.PSObject.Properties['reportFormatVersion'] -or
        @($windowFields | Where-Object { $null -ne $Report.PSObject.Properties[$_] }).Count -gt 0
    if ($hasWindow) {
        if ($Report.reportFormatVersion -ne 2) { throw 'Invalid window metadata: expected report format 2' }
        foreach ($field in $windowFields) {
            $value = $Report.$field
            if ($null -eq $Report.PSObject.Properties[$field] -or $null -eq $value -or
                $value -is [string] -or $value -is [bool] -or
                -not [double]::IsFinite([double]$value) -or [double]$value -lt 0 -or
                [math]::Truncate([double]$value) -ne [double]$value) { throw "Invalid window metadata: $field" }
        }
        $delayMs = $Report.requestedQueryDelaySeconds * 1000
        $elapsedWindow = $Report.queryWindowEndElapsedRealtimeMs - $Report.queryWindowStartElapsedRealtimeMs
        if ($Report.requestedQueryDelaySeconds -gt 120 -or
            $Report.readyEpochMs -le 0 -or
            $Report.queryWindowStartEpochMs -lt ($Report.readyEpochMs + $delayMs) -or
            $Report.queryWindowStartElapsedRealtimeMs -lt ($Report.readyElapsedRealtimeMs + $delayMs) -or
            $Report.queryWindowEndEpochMs -lt $Report.queryWindowStartEpochMs -or
            $elapsedWindow -lt 0 -or
            ($Report.querySamplesMs | Measure-Object -Sum).Sum -gt ($elapsedWindow + 2)) {
            throw 'Invalid window metadata: impossible query window or delay'
        }
    }
    $ordered = @($Report.querySamplesMs | ForEach-Object {
        $value = [double]$_
        if (-not [double]::IsFinite($value) -or $value -lt 0) { throw 'Invalid query timing' }
        $value
    } | Sort-Object)
    if (-not [double]::IsFinite([double]$Report.queryP95Ms) -or
        [math]::Abs($ordered[18] - $Report.queryP95Ms) -gt 0.001 -or
        [math]::Abs($ordered[9] - $Report.queryP50Ms) -gt 0.001) { throw 'Invalid percentile calculation' }
    if ($Report.status -ne 'passed' -or $Report.queryP95Ms -ge 2000) {
        throw "FAILED: physical 10000-passage p95=$($Report.queryP95Ms)ms exceeds 2000ms"
    }
}
