#requires -Version 7.0
<#
Report-only replay of observed Note-concept support order.

Reads a Discovery benchmark JSONL report and answers two questions without any Note text:
  * How deep in retrieval order did the first independently supported passage appear?
  * For scans that exhausted their candidates, how many checks would an initial passage
    budget have deferred?

Candidate order is already score-descending (TopicCandidatePaging), so "rerank by score" cannot
change the order this data describes. A passage budget can only defer candidates; it cannot reach
support sooner. The numbers below quantify what a budget of K would delay.
#>
param([Parameter(Mandatory = $true)][string]$Report)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Report)) { throw "Missing benchmark report: $Report" }
$records = @(Get-Content $Report | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($records.Count -eq 0) { throw 'The report contains no benchmark records' }

function Metric($record, [string]$name) {
    $property = $record.PSObject.Properties[$name]
    if ($property) { return [double]$property.Value }
    return 0.0
}

$rows = foreach ($record in $records) {
    $supportedCount = [int](Metric $record 'work_supportSupportedRankCount')
    $exhaustedCount = [int](Metric $record 'work_supportExhaustedCheckedCount')
    $exhaustedChecks = [int](Metric $record 'work_supportExhaustedChecked')
    $ranks = @(0..3) | ForEach-Object { [int](Metric $record "work_supportSupportedRankBucket$_") }
    [pscustomobject]@{
        Mode = $record.mode
        SupportedScans = $supportedCount
        MeanSupportedRank = if ($supportedCount -gt 0) {
            [math]::Round((Metric $record 'work_supportSupportedRank') / $supportedCount, 2)
        } else { $null }
        Rank0 = $ranks[0]; Rank1 = $ranks[1]; Rank2 = $ranks[2]; Rank3Plus = $ranks[3]
        ExhaustedScans = $exhaustedCount
        MeanChecksPerExhaustedScan = if ($exhaustedCount -gt 0) {
            [math]::Round($exhaustedChecks / $exhaustedCount, 2)
        } else { $null }
        EarlyStopped = [int](Metric $record 'work_conceptScanEarlyStopped')
        ChecksTotal = [int](Metric $record 'work_supportCandidatesChecked')
    }
}
$rows | Format-Table -AutoSize | Out-Host

$totalSupported = ($rows | Measure-Object SupportedScans -Sum).Sum
$totalExhausted = ($rows | Measure-Object ExhaustedScans -Sum).Sum
$exhaustedChecks = ($rows | Measure-Object ChecksTotal -Sum).Sum
$rank0 = ($rows | Measure-Object Rank0 -Sum).Sum
$rank1 = ($rows | Measure-Object Rank1 -Sum).Sum
$rank2 = ($rows | Measure-Object Rank2 -Sum).Sum
$rank3 = ($rows | Measure-Object Rank3Plus -Sum).Sum
$meanExhausted = if ($totalExhausted -gt 0) {
    # Checks are recorded per exhausted scan, so this is the mean the budget subtraction uses.
    [math]::Round(($rows | ForEach-Object {
        if ($null -ne $_.MeanChecksPerExhaustedScan -and $_.ExhaustedScans -gt 0) {
            $_.MeanChecksPerExhaustedScan * $_.ExhaustedScans
        } else { 0 }
    } | Measure-Object -Sum).Sum / $totalExhausted, 2)
} else { $null }

Write-Host ''
Write-Host "Supported scans: $totalSupported; exhausted scans: $totalExhausted; checks total: $exhaustedChecks"
if ($totalSupported -gt 0) {
    Write-Host ("Supported rank distribution: 0={0} 1={1} 2={2} 3+={3}" -f $rank0, $rank1, $rank2, $rank3)
    Write-Host ''
    Write-Host 'Initial passage budget effect (report-only, mean-based approximation):'
    foreach ($budget in 1..3) {
        $delayed = 0
        if ($budget -le 1) { $delayed = $rank1 + $rank2 + $rank3 }
        elseif ($budget -eq 2) { $delayed = $rank2 + $rank3 }
        else { $delayed = $rank3 }
        $deferred = if ($null -ne $meanExhausted) {
            [math]::Round([math]::Max(0, $meanExhausted - $budget) * $totalExhausted, 0)
        } else { 0 }
        Write-Host ("  budget {0}: delays {1} supported Note(s); defers about {2} check(s) on exhausted scans" -f
            $budget, $delayed, $deferred)
    }
} else {
    Write-Host 'No supported scan was observed; the benchmark window did not reach independent support.'
}
