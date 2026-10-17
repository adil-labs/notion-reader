#requires -Version 7.0
<# Amends the user-authorized service lifecycle in live governing sources. Dry-run by default. #>
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$credential = (Get-Content (Join-Path $repo '.env') -Raw).Trim()
$credential = ($credential -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $credential"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$rules = @{
    '3dec9345-e10a-81ab-aa68-cf0b2ee17e45' = @(
        ,@('Keep orchestration process-scoped, no persistent indexing service, and preserve the existing private derived-storage budget and source-removal rules.',
          'A private, non-exported Android bound Discovery service owns the single bulk-work coroutine and cancels it on service destruction. The application holds the binding, independently of screens and tasks. All expensive discovery work, including tokenization, inference, formation, publication filtering and connection retrieval, runs off the main thread. The service does not grant permission to process while backgrounded, during playback or under resource pressure: bulk work still pauses and resumes from durable checkpoints. Do not introduce a foreground notification, sticky restart or a second indexing worker. Preserve the existing private derived-storage budget and source-removal rules.')
    )
    '3dec9345-e10a-81e8-8b28-e7d04672e53e' = @(
        @('A single process-scoped application worker extracts', 'A single worker owned by the private application-bound Android Discovery service extracts'),
        @('Checkpoints resume on an eligible launch; no persistent indexing service is introduced.', 'Checkpoints resume on an eligible launch. A non-exported Android bound service owns and cancels the worker scope; the application holds its binding independently of Activity lifetime. Service callbacks perform no heavy work on the main thread. The existing playback, background and resource-pressure pauses remain mandatory. No foreground notification, sticky restart or duplicate worker is introduced.')
    )
}
$backup = Join-Path $repo ('build/discovery-service-amendment/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$null = New-Item -ItemType Directory -Force $backup
foreach ($page in @('3dec9345-e10a-81ab-aa68-cf0b2ee17e45', '3dec9345-e10a-81e8-8b28-e7d04672e53e')) {
    $blocks = @(); $cursor = $null
    do {
        $uri = "https://api.notion.com/v1/blocks/$page/children?page_size=100"
        if ($cursor) { $uri += "&start_cursor=$cursor" }
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        $blocks += $response.results; $cursor = $response.next_cursor
    } while ($response.has_more)
    $blocks | ConvertTo-Json -Depth 100 | Set-Content (Join-Path $backup "$page.json") -Encoding utf8
    foreach ($rule in $rules[$page]) {
        $matchedBlocks = @($blocks | Where-Object {
            $_.type -eq 'paragraph' -and (($_.paragraph.rich_text | ForEach-Object plain_text) -join '').Contains($rule[0])
        })
        if ($matchedBlocks.Count -eq 0) {
            $alreadyApplied = @($blocks | Where-Object {
                $_.type -eq 'paragraph' -and (($_.paragraph.rich_text | ForEach-Object plain_text) -join '').Contains($rule[1])
            })
            if ($alreadyApplied.Count -eq 1) { Write-Output 'Already applied; live source verified.'; continue }
        }
        if ($matchedBlocks.Count -ne 1) { throw "Expected one source paragraph for: $($rule[0])" }
        $block = $matchedBlocks[0]
        $before = ($block.paragraph.rich_text | ForEach-Object plain_text) -join ''
        $after = $before.Replace($rule[0], $rule[1])
        Write-Output "BEFORE: $before"
        Write-Output "AFTER: $after"
        if ($Apply) {
            $current = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($block.id)" -Headers $headers
            if ((($current.paragraph.rich_text | ForEach-Object plain_text) -join '') -cne $before) { throw 'Source changed since inspection' }
            $rich = @(); for ($offset = 0; $offset -lt $after.Length; $offset += 1900) {
                $rich += @{ type = 'text'; text = @{ content = $after.Substring($offset, [Math]::Min(1900, $after.Length - $offset)) } }
            }
            $body = @{ paragraph = @{ rich_text = $rich } } | ConvertTo-Json -Depth 10
            $null = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($block.id)" -Method Patch -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body))
        }
    }
}
