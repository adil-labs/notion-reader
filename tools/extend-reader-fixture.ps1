#requires -Version 7.0
<#
.SYNOPSIS
    Appends the two threshold-fallback blocks to the Phase 0 fixture page.

.DESCRIPTION
    docs/decisions.md D11 settles that long blocks must split at sentence boundaries but
    deliberately leaves the character threshold unfrozen, because no governing document supplies
    one and the existing fixture only exercises the first rule of the fallback chain.

    These two blocks exercise the rules the first one cannot reach:

      1. a single sentence longer than any plausible threshold, which forces a fall-through to
         the word-boundary rule;
      2. a block with no sentence-ending punctuation at all, which forces a fall-through past the
         sentence rule entirely.

    Blocks are appended to the end of the fixture page. This Notion API version rejects `after`
    as both a body field and a query parameter, so blocks cannot be inserted at a position. For
    the fixture this is harmless: the two blocks land at the end of the final section, which is
    recorded in docs/phase0-fixture-contract.md.

    Idempotent: skips if the blocks are already present.
#>
param([switch]$DryRun)

$ErrorActionPreference = 'Stop'

$FIXTURE_PAGE = '3dec9345-e10a-8180-867d-fa96b49bd30b'
$DRY_RUN = [bool]$DryRun

$token = (Get-Content 'd:\repos\notion-reader\.env' -Raw).Trim()
$token = $token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', ''
$token = $token.Replace('"', '').Trim()

$headers = @{
    Authorization    = "Bearer $token"
    'Notion-Version' = '2026-03-11'
    'Content-Type'   = 'application/json'
}

# One sentence, ~400 chars, with no internal sentence boundary.
$SINGLE_LONG_SENTENCE = 'This single sentence deliberately runs well past any plausible chunk threshold so that a splitter which only understands sentence boundaries has no choice but to fall through to its word boundary rule and break the text somewhere in the middle of a clause rather than at a punctuation mark, which is precisely the fallback path that must be exercised before the threshold constant can be chosen with any confidence at all.'

# ~370 chars, no full stop anywhere.
$NO_SENTENCE_ENDINGS = 'no sentence ending punctuation appears anywhere in this block just commas and semicolons and the occasional dash but never a full stop so a splitter has to fall back again first to a word boundary and if even that is not enough to a hard split at the character limit which is the last resort the fallback chain defines and which should essentially never be reached by ordinary prose'

function RT([string]$text) {
    return ,@(@{ type = 'text'; text = @{ content = $text } })
}

function Get-BlockText($block) {
    $payload = $block.PSObject.Properties[$block.type].Value
    if ($null -eq $payload) { return '' }
    $rt = $payload.PSObject.Properties['rich_text']
    if ($null -eq $rt -or $null -eq $rt.Value) { return '' }
    return ($rt.Value | ForEach-Object { $_.plain_text }) -join ''
}

$existing = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$FIXTURE_PAGE/children?page_size=100" -Headers $headers
$texts = $existing.results | ForEach-Object { Get-BlockText $_ }

Write-Output "fixture has $($existing.results.Count) top-level blocks"

if ($texts | Where-Object { $_.StartsWith('This single sentence deliberately') }) {
    Write-Output '[skip] threshold-fallback blocks already present'
    return
}

$children = @(
    @{ type = 'paragraph'; paragraph = @{ rich_text = (RT $SINGLE_LONG_SENTENCE) } }
    @{ type = 'paragraph'; paragraph = @{ rich_text = (RT $NO_SENTENCE_ENDINGS) } }
)

if ($DRY_RUN) {
    Write-Output "[dry-run] APPEND 2 blocks ($($SINGLE_LONG_SENTENCE.Length) and $($NO_SENTENCE_ENDINGS.Length) chars)"
    return
}

Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$FIXTURE_PAGE/children" -Method Patch -Headers $headers `
    -Body (@{ children = $children } | ConvertTo-Json -Depth 30 -Compress) | Out-Null

$after = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$FIXTURE_PAGE/children?page_size=100" -Headers $headers
Write-Output "appended 2 blocks; fixture now has $($after.results.Count) top-level blocks"
