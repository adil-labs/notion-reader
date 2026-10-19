#requires -Version 7.0
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$credential = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$credential = ($credential -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $credential"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$title = 'User-requested Discovery power eligibility and time estimates'
$paragraphs = @(
    'A longer local embedding runtime is acceptable when it provides independently demonstrated better discovery quality. Do not reject a candidate solely for slower processing. Model replacement still requires explicit acquisition, model/tokenizer identity migration, supported Android execution, privacy, memory/storage, cancellation, playback and independent quality verification; host benchmark speed is not a production acceptance verdict.',
    'Model download, Resume indexing and Rebuild index are explicit requests to prepare cached Notes under the existing indexing lifecycle; Pause and index/model clearing retain their existing meanings. Bulk Discovery work may run only while the device is connected to external power or its reported battery level is at least thirty percent. Unknown battery status without external power is ineligible. Battery/power changes pause and resume the existing single worker from compatible checkpoints; they do not create another scheduler, foreground service, sticky restart or hidden model download. Existing foreground, playback and resource-pressure pauses remain mandatory. Power deferral never delays mandatory source withdrawal or prevents reading, search, listening or inspection of previous valid discoveries.',
    'Discover and Discovery settings show cached/indexed Note coverage, a clear waiting-for-power reason and a coarse estimated remaining embedding-time range derived from the active model and actual device inference throughput over remaining non-reused passages. Exclude pauses and foreground-query waits from throughput observations, account for reusable compatible embeddings and reset estimates when model/source/reset work changes. Show estimating on this device until enough measurements exist. Separate embedding time from subsequent subject and connection analysis: when those durations cannot be estimated reliably, say they are additional and still being estimated rather than claim an embedding-only estimate is the total completion time. Estimates are approximate active processing time, not deadlines; they may change with Note length, temperature, load or changed cached content. Progress changes must not steal focus or produce constant TalkBack announcements.'
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
