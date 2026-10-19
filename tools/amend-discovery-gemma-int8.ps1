#requires -Version 7.0
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$credential = (Get-Content (Join-Path $repoRoot '.env') -Raw).Trim()
$credential = ($credential -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $credential"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$title = 'User-requested EmbeddingGemma 2 INT8 Android integration'
$paragraphs = @(
    'The user requests integration and device verification of EmbeddingGemma 2 INT8 as the optional local Discovery embedding pipeline. This amendment supersedes earlier requirements to retain MiniLM and earlier statements that model replacement is not authorized. Retain the sole existing section-aware formation engine, process-owned worker and evidence-backed subject hierarchy and connection catalog. Host graph benchmark results do not establish shipping-engine quality. Independent release acceptance, privacy, source withdrawal, playback and human TalkBack gates remain binding and must be reported separately from successful integration.',
    'Acquire the model only through the existing explicit optional download flow. Pin the onnx-community/embeddinggemma-2-ONNX export revision daa72c51243991dfcaf9f9137d2c573d8f7790c0 and verify the INT8 graph, external weights and tokenizer assets before atomic installation. Use the exported sentence_embedding output at 768 dimensions, text-only inputs and local ONNX execution. Include model, tokenizer, instruction, dimension and preprocessing identities in compatibility checks. Never reuse MiniLM vectors or incompatible derived checkpoints. Preserve explicit interests, queues, listening state and unrelated settings during migration; never silently update or send private content to a service.',
    'Tokenization must match the pinned reference including special tokens, Unicode, whitespace, byte fallback and source offsets. Account for task instruction and special-token overhead when forming passage inputs; never silently truncate source evidence. Validate the actual Android provider against fixed host token and vector references. Calibrate search, membership and connection decisions for the new embedding space using the shipping pipeline; do not copy MiniLM similarity thresholds or present similarity as confidence. Device verification must establish actual model execution, indexed source coverage, published supported subjects, paired connections, search and navigation using inspectable public or purpose-built evidence, without replacing private device content.',
    'Discover and Settings identify EmbeddingGemma 2 INT8 and its complete download size and acquisition state. Expose loading, indexing, power waiting, errors, partial coverage and approximate device-derived embedding-time estimates truthfully through existing accessible surfaces. Preserve existing foreground, playback, resource-pressure and thirty-percent-or-connected power eligibility. Keep progress passive and Back/focus restoration stable. A successful small device journey does not establish representative release quality, full-scale memory/time/storage or human speech acceptance.'
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
