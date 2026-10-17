#requires -Version 7.0
# User-approved clarification: evaluation is development work; private Note review is optional.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$token = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$token = ($token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $token"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$title = 'Automatic Discovery evaluation and authorized debug trial (2026-10-17)'
$text = @(
    'Discovery is automatic. End users are not required to label, rate, validate or approve discovered topics or supporting passages before using it. Follow and Unfollow express a personal choice, not a topic-quality judgment. Human quality evaluation is developer acceptance work on public, consented or purpose-built representative evaluation Notes; it must not require any user to submit or review their private library. Private-library feedback is optional and stays local unless the user explicitly chooses otherwise. Preserve the existing evaluation thresholds and distinguish verified human judgments, provisional annotations and unrated cases.',
    'The user has authorized a debug-build trial of the implemented replacement Discovery engine on their Android device. This supersedes earlier wording that every experimental flag must stay disabled until all acceptance gates pass: debug builds may enable the replacement for this trial while general release builds retain the baseline. This is not a declaration that quality, memory, playback or human TalkBack acceptance has passed. Keep failed and unmeasured gates recorded, including the latest physical-device additional-memory result, and retain the existing gates for general release enablement. The trial uses the existing local MiniLM model and automatic indexing lifecycle, without a new model download, required private-data review, source-cache reset, saved-interest reset or playback-command change.'
)
foreach ($id in @('3dec9345-e10a-81ab-aa68-cf0b2ee17e45', '3dec9345-e10a-81b6-9cd2-d54532ad6fcd', '3dec9345-e10a-81e8-8b28-e7d04672e53e')) {
    $blocks = @(); $cursor = $null
    do {
        $uri = "https://api.notion.com/v1/blocks/$id/children?page_size=100"
        if ($cursor) { $uri += "&start_cursor=$cursor" }
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        $blocks += $response.results; $cursor = $response.next_cursor
    } while ($response.has_more)
    if ($blocks | Where-Object { $_.type -eq 'heading_1' -and (($_.heading_1.rich_text | ForEach-Object plain_text) -join '') -in @($title, 'Developer evaluation and authorized debug trial') }) {
        Write-Output "Already clarified $id"; continue
    }
    $children = @(@{ object = 'block'; type = 'heading_1'; heading_1 = @{ rich_text = @(@{ type = 'text'; text = @{ content = $title } }) } })
    foreach ($paragraph in $text) {
        if ($paragraph.Length -gt 2000) { throw 'Notion rich-text limit exceeded' }
        $children += @{ object = 'block'; type = 'paragraph'; paragraph = @{ rich_text = @(@{ type = 'text'; text = @{ content = $paragraph } }) } }
    }
    $body = @{ children = $children } | ConvertTo-Json -Depth 12 -Compress
    $null = Invoke-RestMethod -Method Patch -Uri "https://api.notion.com/v1/blocks/$id/children" -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    Write-Output "Clarified $id"
}
