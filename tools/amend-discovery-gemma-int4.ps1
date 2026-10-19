#requires -Version 7.0
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$credential = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$credential = ($credential -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $credential"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$title = 'User-requested EmbeddingGemma 2 INT4 default'
$paragraphs = @(
    'The user requests that EmbeddingGemma 2 INT4 become the default optional local Discovery model, prioritizing measured local inference speed and the smaller pack. This explicitly supersedes the INT8 precision/default requirement in the prior Gemma integration amendment. Keep local ONNX Runtime, the text-only 768-dimensional sentence_embedding output, instruction/tokenizer/source evidence semantics, the sole section-aware exact formation engine and automatic process-owned background work. Benchmark quality remains publisher-label calibration rather than human release acceptance.',
    'Pin onnx-community/embeddinggemma-2-ONNX revision daa72c51243991dfcaf9f9137d2c573d8f7790c0. Select model_q4.onnx (490742 bytes, SHA256 f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9) and model_q4.onnx_data (174028800 bytes, SHA256 c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49), with the existing verified tokenizer/config assets. The complete pack is 206696682 bytes, displayed approximately as 207 MB. Give INT4 a precision-and-artifact-specific identity; reject INT8 vectors and derived checkpoints rather than mixing embedding spaces.',
    'Existing optional acquisition and explicit download consent remain binding. Recognize an old INT8 installation as incompatible without deleting its bytes merely during startup validation or silently downloading a replacement. A successful explicitly requested verified installation may atomically replace the old model. Preserve Notes, explicit interests, Saved Queues, listening state and unrelated settings; only incompatible derived Discovery state is eligible for normal identity invalidation. Do not migrate private phone content as part of developer verification.',
    'Discover and Settings identify INT4 and its actual complete pack size. Revalidate the shipping Android provider against pinned INT4 host vector references and retain INT8 benchmark fixtures under their original identities. Quantization can change membership outcomes; document INT4 policy calibration and independent quality limits. Comparison progress must describe completed passage/section-level work truthfully rather than imply a forecast or a Note-pair count. Preserve automatic resumable processing, exact source coverage, accessibility and existing power/playback/privacy gates.'
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
