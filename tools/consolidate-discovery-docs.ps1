#requires -Version 7.0
<#
.SYNOPSIS
Consolidates dated Discovery amendments in their governing Notion sources.
.DESCRIPTION
Dry-run by default. -Apply patches only matched blocks, retaining their IDs and all
other content, including the Integration domain-model code block. Backups and a
reviewable before/after plan are saved in ignored build storage before any writes.
Unexpected source changes abort; reruns validate the already-consolidated text.
#>
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$token = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$token = ($token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $token"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$backup = Join-Path $repoRoot ('build/doc-consolidation/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$null = New-Item -ItemType Directory -Force $backup
function Read-Blocks($id) {
    $blocks = @(); $cursor = $null
    do {
        $uri = "https://api.notion.com/v1/blocks/$id/children?page_size=100"
        if ($cursor) { $uri += "&start_cursor=$cursor" }
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        $blocks += $response.results; $cursor = $response.next_cursor
    } while ($response.has_more)
    return $blocks
}
function Block-Text($block) { return (($block.($block.type).rich_text | ForEach-Object plain_text) -join '') }
$pages = @('3dec9345-e10a-81ab-aa68-cf0b2ee17e45', '3dec9345-e10a-81b6-9cd2-d54532ad6fcd', '3dec9345-e10a-81e8-8b28-e7d04672e53e')
$common = @(
    @('Trustworthy Discovery delivery contract (2026-10-17)', 'Automatic topics: formation, publication and acceptance'),
    @('Independent explicit Note references (2026-10-17)', 'Independent explicit Note references'),
    @('Automatic Discovery evaluation and authorized debug trial (2026-10-17)', 'Developer evaluation and authorized debug trial'),
    @('This clarification supersedes the earlier rule limiting Named references to already semantically eligible pairs. Retrieve', 'In the replacement engine, retrieve'),
    @('Implement independent retrieval only behind the experimental engine flag until the governing gates pass; the baseline classification policy remains unchanged.', 'Independent retrieval uses the experimental engine flag. General release enablement requires the governing gates; the authorized debug trial is permitted as described below. The release baseline retains classification of already eligible pairs.'),
    @('This supersedes earlier wording that every experimental flag must stay disabled until all acceptance gates pass: debug builds may enable the replacement for this trial while general release builds retain the baseline.', 'Debug builds may enable the replacement for this trial while general release builds retain the baseline.'),
    @('Apply the Product contract quality and physical Android acceptance gates before enabling the replacement engine;', 'Apply the Product contract quality and physical Android acceptance gates before general release enablement of the replacement engine;'),
    @('The experimental flag stays disabled until the governing human-quality and physical Android gates pass.', 'General release enablement requires the governing human-quality and physical Android gates. The authorized debug trial below is permitted while those gates remain open.'),
    @('Before enabling the replacement engine, measure', 'Before general release enablement of the replacement engine, measure'),
    @('failed or unmeasured gates block default enablement.', 'failed or unmeasured gates block general release enablement.'),
    @('This amendment governs automatic topic formation and publication and supersedes earlier three-Note publication minima, anchor-cap termination, and any blanket exclusion based on Non-goals, deferred-work or out-of-scope section headings.', 'The replacement engine uses the following automatic topic formation and publication requirements. Legacy baseline anchor limits and publication minima do not apply to this engine; non-goal, deferred-work and out-of-scope headings do not blanket-exclude substantive discussion.'),
    @('This amendment extends the semantic Discovery screen with separate', 'The semantic Discovery screen presents separate'),
    @('The Trustworthy Discovery delivery amendment in the Product and UI contracts governs the replacement engine.', 'The automatic topic formation and publication requirements in the Product and UI contracts govern the replacement engine.'),
    @('Classification annotates eligible existing pairs; it does not bypass body-evidence eligibility or admission thresholds.', 'In the release baseline, classification annotates eligible existing pairs without bypassing body-evidence eligibility or admission thresholds. The replacement retrieves explicit references independently as specified below.'),
    @('Those weak passages remain searchable but cannot independently establish automatic membership or connections.', 'Those weak passages remain searchable but cannot independently establish automatic membership or Similar passages connections; explicit citations may establish Named references under the independent retrieval requirements below.'),
    @('Reference-only/link-list/navigation boilerplate is excluded from group/connection evidence without deleting or hiding it from semantic search.', 'Reference-only/link-list/navigation boilerplate is excluded from thematic group/Similar passages evidence without deleting or hiding it from semantic search. Explicit citations may independently establish Named references in the replacement engine.'),
    @('Classify existing eligible pairs with a pure, versioned presentation policy:', 'In the release baseline, classify existing eligible pairs with a pure, versioned presentation policy:'),
    @('Its order is semantic search with facets and coverage, Topics in your Notes, Connections between Notes and one View your interests entry.', 'Its order is semantic search with facets and coverage, Topics in your Notes, Emerging topics, Connections between Notes and one View your interests entry.')
)
$allPlans = @(); $originals = @{}
foreach ($page in $pages) {
    $blocks = @(Read-Blocks $page); $originals[$page] = $blocks
    $blocks | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $backup "$page.json") -Encoding utf8
    foreach ($block in $blocks) {
        if ($block.type -notin @('paragraph', 'heading_1')) { continue }
        $before = Block-Text $block; $after = $before
        foreach ($rule in $common) { $after = $after.Replace($rule[0], $rule[1]) }
        if ($after -cne $before) {
            $allPlans += [pscustomobject]@{ Page = $page; Id = $block.id; Type = $block.type; Before = $before; After = $after }
        }
    }
    $headings = @($blocks | Where-Object type -eq 'heading_1' | ForEach-Object { Block-Text $_ })
    if (-not (($headings -contains $common[0][0]) -or ($headings -contains $common[0][1]))) { throw "Expected Discovery contract absent: $page" }
}
$allPlans | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $backup 'plan.json') -Encoding utf8
Write-Output "Review plan and complete source backups: $backup"
Write-Output "Matched $($allPlans.Count) blocks across $($pages.Count) governing pages. Apply=$Apply"
if (-not $Apply) { return }
foreach ($change in $allPlans) {
    $current = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($change.Id)" -Headers $headers
    if ($current.type -cne $change.Type -or (Block-Text $current) -cne $change.Before) { throw "Source changed since planning: $($change.Id)" }
    $rich = @(); $value = $change.After
    while ($value.Length -gt 0) {
        $length = [Math]::Min(1900, $value.Length)
        $rich += @{ type = 'text'; text = @{ content = $value.Substring(0, $length) } }
        $value = $value.Substring($length)
    }
    $payload = @{ $change.Type = @{ rich_text = $rich } } | ConvertTo-Json -Depth 12 -Compress
    $null = Invoke-RestMethod -Method Patch -Uri "https://api.notion.com/v1/blocks/$($change.Id)" -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($payload))
}
foreach ($page in $pages) {
    $updated = @(Read-Blocks $page); $old = $originals[$page]
    if ($updated.Count -ne $old.Count) { throw "Unexpected block-count change: $page" }
    for ($i = 0; $i -lt $old.Count; $i++) {
        $expected = @($allPlans | Where-Object Id -eq $old[$i].id)
        $text = if ($expected.Count) { $expected[0].After } else { Block-Text $old[$i] }
        if ($updated[$i].id -cne $old[$i].id -or $updated[$i].type -cne $old[$i].type -or (Block-Text $updated[$i]) -cne $text) { throw "Unexpected source alteration: $page block $i" }
        if ($old[$i].type -eq 'code' -and ($updated[$i].code | ConvertTo-Json -Depth 30 -Compress) -cne ($old[$i].code | ConvertTo-Json -Depth 30 -Compress)) { throw "Code block changed: $page block $i" }
    }
}
Write-Output 'Verified every block identity/type/text, including untouched source and full code blocks.'
