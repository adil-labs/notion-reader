#requires -Version 7.0
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$credential = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$credential = ($credential -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $credential"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$title = 'Approved automatic Discovery scheduling and effort settings'
$paragraphs = @(
    'This approved automatic Discovery amendment supersedes earlier foreground-only bulk-work restrictions, bound-service-exclusive worker ownership, and requirements to press Continue at work-slice boundaries. After explicit setup and verified optional model acquisition, cached-Note embeddings, topics and connections are processed automatically without further user interaction. Work may run with the screen off or app task closed during Android-granted execution windows. Work remains deferred by Android scheduling restrictions; force-stop requires reopening. No uninterrupted execution or completion deadline is promised.',
    'A process-owned coordinator serializes all foreground and persistent WorkManager requests through one bulk execution gate and the same Discovery engine, stores and source/model/policy/reset checkpoints. WorkManager grants bounded ordinary non-expedited execution windows and recovery across ordinary process death and reboot. Replace the former bound-service-only ownership deliberately; do not retain a competing infinite loop, second inference worker, permanent foreground service, notification or sticky restart. Expensive work remains off the main thread. Foreground queries retain priority; playback and resource pressure suspend bulk work. Source withdrawal and access-loss handling remain mandatory while bulk work is deferred.',
    'Discovery effort is persisted in Settings with Balanced as the default and Charging only as the alternative. Balanced permits bulk work on external power or known battery of at least thirty percent; unknown battery without external power waits. Charging only permits bulk work exclusively on external power. Effort controls execution availability, never evidence quality, similarity thresholds, eligible-source coverage or an artificial topic-count limit. Source/model changes, setup completion and restored eligibility arrange work automatically; unfinished slices resume without a new edit or Continue action.',
    'Explicit user Pause remains persisted and requires explicit Resume. Index clear and model deletion retain their rebuilding-disabled semantics; Resume re-enables automatic work, and model acquisition remains explicit. Reset/model deletion cancel queued work and invalidate stale commits. Existing private storage budgets, source eligibility, cache-only inference, inspection pinning, independent interests, frozen listening queues and privacy rules remain binding. No automatic remote-library download or silent model update is introduced.',
    'Discover and Settings distinguish processing, waiting for power, waiting for an Android execution window, explicit user pause and incomplete storage coverage. Remove Continue discovery for ordinary unfinished work. Refresh only adopts a new consistent generation in an already inspected view and never starts or completes processing. Keep indexed versus eligible Note and processed versus eligible section coverage visible. Show approximate measured remaining embedding time separately from still-unestimated topic/connection analysis; exclude OS waits and other pauses from active processing estimates. Status changes never steal focus or create constant TalkBack announcements.'
)
foreach ($page in @('3dec9345-e10a-81ab-aa68-cf0b2ee17e45', '3dec9345-e10a-81b6-9cd2-d54532ad6fcd', '3dec9345-e10a-81e8-8b28-e7d04672e53e')) {
    $blocks = @(); $cursor = $null
    do {
        $uri = "https://api.notion.com/v1/blocks/$page/children?page_size=100"
        if ($cursor) { $uri += "&start_cursor=$cursor" }
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        $blocks += $response.results; $cursor = $response.next_cursor
    } while ($response.has_more)
    if ($blocks | Where-Object { $_.type -eq 'heading_1' -and (($_.heading_1.rich_text | ForEach-Object plain_text) -join '') -ceq $title }) {
        Write-Output "Already applied to $page"; continue
    }
    Write-Output "Amend $page with $title"
    foreach ($paragraph in $paragraphs) { Write-Output $paragraph }
    if ($Apply) {
        $children = @(@{ object = 'block'; type = 'heading_1'; heading_1 = @{ rich_text = @(@{ type = 'text'; text = @{ content = $title } }) } })
        foreach ($paragraph in $paragraphs) {
            if ($paragraph.Length -gt 2000) { throw 'Paragraph exceeds rich-text limit' }
            $children += @{ object = 'block'; type = 'paragraph'; paragraph = @{ rich_text = @(@{ type = 'text'; text = @{ content = $paragraph } }) } }
        }
        $body = @{ children = $children } | ConvertTo-Json -Depth 12 -Compress
        $null = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$page/children" -Method Patch -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body))
    }
}
