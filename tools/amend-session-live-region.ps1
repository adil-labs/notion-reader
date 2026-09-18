#requires -Version 7.0
<#
.SYNOPSIS
    Amends the Active Queue & Reader Session spec's live-region sentence, in Notion.

.DESCRIPTION
    The Active Queue & Reader Session spec is a Notion page mirrored into docs/notion/. It describes the
    announcements an inexact restore produces as "live-region announcements". Read literally that makes a
    live region the *mechanism* of the announcement, which leaves a listener with the screen off - and no
    screen reader running - unannounced. The Core Player & TTS Playback spec and the Background Playback
    spec both depend on the other reading: the reader's own TTS speaks the announcement, and a live region
    is an *additional* exposure of the same fact.

    A hand-authored document cannot amend a mirrored one (docs/decisions.md 23.9), so the amendment has to
    be made here second: in Notion, then re-exported.

    Dry-run by default. Idempotent: re-running after the amendment has been applied reports so and changes
    nothing.

    READ THIS BEFORE PATCHING ANYTHING ELSE IN THIS SPEC
    ----------------------------------------------------
    The integration spec stores its whole reader domain model in a single code block, and patching a block
    replaces all of its rich_text - so an additive amendment can silently delete neighbouring
    declarations. This script is written for a *bulleted list item*, which is a whole sentence, and it
    prints the target's full current text before it would change it. Do the same for anything else.

.EXAMPLE
    pwsh -File tools/amend-session-live-region.ps1 -Inspect
    pwsh -File tools/amend-session-live-region.ps1 -Apply
#>
param(
    [switch]$Inspect,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repoRoot '.env'
if (-not (Test-Path $envPath)) {
    throw "Cannot find $envPath. It must hold the Notion token (key=value or a bare value)."
}

$token = (Get-Content $envPath -Raw).Trim()
$token = $token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', ''
$token = $token.Replace('"', '').Trim()

$headers = @{
    Authorization    = "Bearer $token"
    'Notion-Version' = '2026-03-11'
    'Content-Type'   = 'application/json'
}

$pageId = '3dec9345-e10a-814c-a8f3-c8b3539ac8e1'

# The sentence as it stands, and the sentence that should replace it.
#
# Matching is on a distinctive fragment rather than on the whole text, because Notion's stored rich_text
# and the mirror's wrapped lines differ in whitespace. [regex]::IsMatch is used rather than -match because
# -match is case-insensitive, and an amendment that matches the wrong block is not a failure this script
# could detect afterwards.
$matchFragment = 'live-region announcements'
$alreadyAppliedFragment = "reader's own TTS engine"
$newText = "The announcements required by inexact restore are spoken by the reader's own TTS engine, and " +
    "are also exposed as live regions, so that they are heard when no screen reader is running. They must " +
    "be brief, and they must not replace or delay the Note's content beyond the announcement itself."

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
    return , $all
}

function Get-PlainText($block) {
    $payload = $block.PSObject.Properties[$block.type].Value
    if ($null -eq $payload) { return '' }
    $rt = $payload.PSObject.Properties['rich_text']
    if ($null -eq $rt -or $null -eq $rt.Value) { return '' }
    return ($rt.Value | ForEach-Object { $_.plain_text }) -join ''
}

$targets = @()
foreach ($b in (Get-Children $pageId)) {
    $text = Get-PlainText $b
    if ($text -and [regex]::IsMatch($text, [regex]::Escape($matchFragment))) {
        $targets += [pscustomobject]@{ Block = $b; Text = $text }
    }
}

if ($targets.Count -eq 0) {
    throw "No block containing '$matchFragment' was found on the spec page. Nothing was changed."
}

if ($targets.Count -gt 1) {
    # Refusing rather than guessing: an amendment applied to the wrong block is indistinguishable from a
    # clean run afterwards, which is the failure this project keeps meeting in another shape.
    Write-Output "REFUSING: $($targets.Count) blocks match '$matchFragment'. Narrow the match first."
    $targets | ForEach-Object { Write-Output "  $($_.Block.id) : $($_.Text)" }
    exit 2
}

$target = $targets[0]
$block = $target.Block

Write-Output "Match fragment : $matchFragment"
Write-Output "Block id       : $($block.id)"
Write-Output "Block type     : $($block.type)"
Write-Output ''
Write-Output 'CURRENT FULL TEXT (this is what a patch replaces):'
Write-Output $target.Text
Write-Output ''
Write-Output 'PROPOSED FULL TEXT:'
Write-Output $newText
Write-Output ''

if ([regex]::IsMatch($target.Text, [regex]::Escape($alreadyAppliedFragment))) {
    Write-Output 'ALREADY APPLIED - nothing to do.'
    exit 0
}

if (-not $Apply) {
    Write-Output 'DRY RUN - nothing was changed. Re-run with -Apply to patch the block.'
    exit 0
}

$body = @{
    $block.type = @{
        rich_text = @(
            @{ type = 'text'; text = @{ content = $newText } }
        )
    }
} | ConvertTo-Json -Depth 10 -Compress

$result = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($block.id)" `
    -Headers $headers -Method Patch -Body $body

$verify = Get-PlainText $result
if (-not [regex]::IsMatch($verify, [regex]::Escape($alreadyAppliedFragment))) {
    throw "The patch did not take: the block still does not contain '$alreadyAppliedFragment'."
}

Write-Output 'APPLIED.'
Write-Output ''
Write-Output 'The page, as it now reads:'
Write-Output $verify
Write-Output ''
Write-Output 'Now re-export and DIFF the mirror - an exporter bug and a spec change look identical otherwise:'
Write-Output '  pwsh -File tools/export-notion-doc.ps1 -All'
Write-Output '  git diff -- docs/notion'
