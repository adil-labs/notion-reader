#requires -Version 7.0
<#
.SYNOPSIS
    Captures the Phase 0 fixture page as an offline test snapshot.

.DESCRIPTION
    Dumps the fixture page's metadata and every level of nested block children into a single JSON
    snapshot keyed by block id, so a fake NotionApi can serve exactly what the real API served
    without a token or a network.

    The reader's fetch path calls retrieveBlockChildren(pageId) first and then once per block with
    children, so a map of blockId -> children[] reproduces it faithfully.

    Read-only against Notion. Writes only into app/src/test/resources/fixtures/.
#>

$ErrorActionPreference = 'Stop'

$FIXTURE_PAGE = '3dec9345-e10a-8180-867d-fa96b49bd30b'
$outFile = 'd:\repos\notion-reader\app\src\test\resources\fixtures\reader-fixture.json'

$token = (Get-Content 'd:\repos\notion-reader\.env' -Raw).Trim()
$token = $token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', ''
$token = $token.Replace('"', '').Trim()

$headers = @{
    Authorization    = "Bearer $token"
    'Notion-Version' = '2026-03-11'
}

function Get-Children($blockId) {
    $all = @()
    $cursor = $null
    do {
        $url = "https://api.notion.com/v1/blocks/$blockId/children?page_size=100"
        if ($cursor) { $url += "&start_cursor=$cursor" }
        $r = Invoke-RestMethod -Uri $url -Headers $headers
        $all += $r.results
        $cursor = $r.next_cursor
    } while ($r.has_more)
    # Leading comma: PowerShell unwraps a single-element array on return, which would serialise a
    # one-child block as a JSON object instead of an array and break the snapshot's shape.
    return ,$all
}

Write-Output 'Fetching fixture page...'
$page = Invoke-RestMethod -Uri "https://api.notion.com/v1/pages/$FIXTURE_PAGE" -Headers $headers

$childrenMap = [ordered]@{}
# Use a List as a queue with an index cursor. Slicing an array to pop the head is unsafe here:
# `$a[1..0]` in PowerShell produces a DESCENDING range (1,0), not an empty array, so a
# single-element queue re-adds its own element and the loop never terminates.
$visited = [System.Collections.Generic.List[string]]::new()
$visited.Add($FIXTURE_PAGE)
$total = 0

for ($i = 0; $i -lt $visited.Count; $i++) {
    $id = $visited[$i]

    $blocks = Get-Children $id
    $childrenMap[$id] = $blocks
    $total += $blocks.Count

    foreach ($b in $blocks) {
        if ($b.has_children -and -not $visited.Contains($b.id)) { $visited.Add($b.id) }
    }
}

$snapshot = [ordered]@{
    capturedFromPage = $FIXTURE_PAGE
    capturedAt       = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    notionVersion    = '2026-03-11'
    note             = 'Offline snapshot of the Phase 0 fixture page. Regenerate with tools/capture-reader-fixture.ps1.'
    page             = $page
    children         = $childrenMap
}

$dir = Split-Path -Parent $outFile
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$snapshot | ConvertTo-Json -Depth 40 | Set-Content -Path $outFile -Encoding UTF8

Write-Output "blocks captured : $total"
Write-Output "parent ids      : $($childrenMap.Keys.Count)"
Write-Output "written         : $outFile"
Write-Output ("size            : {0:N0} KB" -f ((Get-Item $outFile).Length / 1KB))
