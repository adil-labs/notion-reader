#requires -Version 7.0
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$credential = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$credential = ($credential -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $credential"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$title = 'Automatic local backends and evidence-preserving Discovery efficiency'
$paragraphs = @(
    'The user delegates implementation decisions for appropriate per-device local execution and cost reduction. Keep the pinned INT4 model and existing explicit acquisition. Select a locally available accelerated ONNX backend only after its actual embedding output agrees with the same-input CPU reference. Validate finite nonzero output, disable a failing or numerically inconsistent accelerated backend for the process, and retry on CPU transparently. Backend availability is a capability result, not a device marketing name. No fixed-shape research NPU graph is production support for arbitrary Notes.',
    'Reduce redundant work while retaining the sole exact section-aware engine and all source evidence. Retain one process-owned prepared generation keyed by source revisions, metadata, embedding identity, policy and algorithm parameters; reuse preparation and validated in-process checkpoints between work slices. Revalidate durable imported checkpoints after process death. Release optional acceleration memory under pressure and invalidate it when sources, model, policy or metadata change. Orderly scheduling/query/power yields save completed work, including the handoff from formation to connections; source withdrawal, cancellation and reset remain fail-closed.',
    'Existing authored areas and topics may organize evidence into acceleration buckets and guide reuse. They are optional hints, never truth labels or hard partitions: unlabelled Notes and cross-area/topic relationships remain eligible. A bucket may skip detailed similarity work only when a conservative numerical bound proves every skipped passage below the same existing threshold. Otherwise perform the exact original score and strongest-passage/tie decision. Preserve current publication, hierarchy, source-context and literal paired evidence requirements. Verify parity against exhaustive processing and report actual work avoided.',
    'Exact model-input reuse may avoid repeated local inference within bounded process-owned caches. Reuse requires the complete embedding identity and exactly equal input, never just a matching area/topic label, title, passage ID or document revision. Bind reused vectors to the current source passage, offsets and metadata. Clear optional memoized data on model release/resource pressure and never export private content. Progress reports distinguish Notes, sections, passage groups and completed checks, and must not present an operation counter as remaining work or a completion ETA.'
)
ayback/privacy gates.'
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
