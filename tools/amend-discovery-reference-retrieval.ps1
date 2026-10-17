#requires -Version 7.0
# Explicit authoritative clarification; idempotent append, no domain-model block patch.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$token = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$token = ($token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $token"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$title = 'Independent explicit Note references (2026-10-17)'
$text = 'This clarification supersedes the earlier rule limiting Named references to already semantically eligible pairs. Retrieve explicit Note references independently of semantic similarity thresholds, cross-topic membership and thematic body-evidence admission. An explicit instruction or citation naming the unique complete current title of another eligible cached Note establishes a reference, including instructions in reference lists; a bare coincidental or partial title mention, ambiguous title, standalone heading, or negated instruction does not. Reference-list evidence never independently establishes topic membership or a Similar passages relationship. Both endpoints must remain currently cached and indexed, with source revision and title uniqueness checked. Display the literal referencing passage and a literal target passage as target context; explain that the target excerpt is context, not proof of thematic similarity, agreement or a verified Notion hyperlink. Keep Named references separate from Similar passages in lists, evidence descriptions and evaluation. Preserve unordered canonical pair identities, explicit Refresh, source-removal/privacy rules and session isolation. Implement independent retrieval only behind the experimental engine flag until the governing gates pass; the baseline classification policy remains unchanged.'
foreach ($id in @('3dec9345-e10a-81ab-aa68-cf0b2ee17e45', '3dec9345-e10a-81b6-9cd2-d54532ad6fcd', '3dec9345-e10a-81e8-8b28-e7d04672e53e')) {
    $blocks = @(); $cursor = $null
    do {
        $uri = "https://api.notion.com/v1/blocks/$id/children?page_size=100"
        if ($cursor) { $uri += "&start_cursor=$cursor" }
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        $blocks += $response.results; $cursor = $response.next_cursor
    } while ($response.has_more)
    if ($blocks | Where-Object { $_.type -eq 'heading_1' -and (($_.heading_1.rich_text | ForEach-Object plain_text) -join '') -in @($title, 'Independent explicit Note references') }) {
        Write-Output "Already clarified $id"; continue
    }
    $children = @(
        @{ object = 'block'; type = 'heading_1'; heading_1 = @{ rich_text = @(@{ type = 'text'; text = @{ content = $title } }) } },
        @{ object = 'block'; type = 'paragraph'; paragraph = @{ rich_text = @(@{ type = 'text'; text = @{ content = $text } }) } }
    )
    $body = @{ children = $children } | ConvertTo-Json -Depth 12 -Compress
    $null = Invoke-RestMethod -Method Patch -Uri "https://api.notion.com/v1/blocks/$id/children" -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    Write-Output "Clarified $id"
}
