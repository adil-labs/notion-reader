#requires -Version 7.0
<#
.SYNOPSIS
    Produces an "edited" snapshot of the Phase 0 fixture page, then restores the page.

.DESCRIPTION
    Phase 5 of the integration spec asks for proof that "unchanged content produces unchanged
    identities across edits". Our chunk and section ids are derived from Notion block ids, so that
    claim reduces to a fact about the Notion API rather than about our own code:

        does a block keep its id when it is edited, and do other blocks keep theirs?

    Our mapper cannot renumber anything (ids come from block.id), so a test that fed it a
    hand-modified JSON would prove only that our own code is self-consistent. This script therefore
    asks Notion the real question, by making real edits and capturing what the API reports.

    Three edits, all chosen to be reversible, and together they cover every identity the spec names
    (page, section, block, chunk):

      1. MODIFY  one paragraph's text in place, and restore it afterwards. A block's own content
                 changing must not change its id or its chunk id — this is the edit that matters most,
                 because "the same block, now different" is the normal case for a note being edited.

      2. CREATE  one paragraph appended to the end of the page, and delete it afterwards. Tests that
                 adding content does not disturb any existing block's identity, and — because the
                 appended block is then really deleted — that removing content does not renumber the
                 rest. The restore check below is what proves the delete left the page intact.

      3. BLANK   one heading, and restore its text afterwards. This is the strongest of the three and
                 the reason the script exists: an empty heading produces no section and no chunk (D4),
                 so blanking one *removes a whole section and shifts every later paragraph's index*.
                 If anything downstream keyed on position, this is the edit that would silently move a
                 saved reading position to the wrong content. Ids must be unaffected.

    Only the last block is ever appended and only that block is ever archived, so no existing block
    moves as a side effect of the edit itself. The script never uses the `after` parameter, because
    this API version rejects it.

    Writes into app/src/test/resources/fixtures/. Restores the page before exiting.
#>

$ErrorActionPreference = 'Stop'

$FIXTURE_PAGE = '3dec9345-e10a-8180-867d-fa96b49bd30b'
$fixtureDir = 'd:\repos\notion-reader\app\src\test\resources\fixtures'
$editedFile = Join-Path $fixtureDir 'reader-fixture-edited.json'

# The paragraph that gets edited in place, and the text it must be restored to.
$MODIFIED_BLOCK = '3dec9345-e10a-81ce-a1a0-f5eaf14193b1'   # block 1, first paragraph
$ORIGINAL_TEXT = 'Intro paragraph before any heading.'
$EDITED_TEXT = 'Intro paragraph before any heading, edited in place to prove the block id survives.'

# The heading that gets blanked, and the text it must be restored to. A single space rather than an
# empty rich_text array: the mapper trims, so either is an "empty heading" per D4, and a space cannot
# be rejected as a validation error.
$BLANKED_HEADING_BLOCK = '3dec9345-e10a-81ad-afda-e576ba917ae4'   # block 7, heading_2
$BLANKED_HEADING_TYPE = 'heading_2'
$ORIGINAL_HEADING_TEXT = 'Secondary Heading'
$BLANKED_TEXT = ' '

$token = (Get-Content 'd:\repos\notion-reader\.env' -Raw).Trim()
$token = $token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', ''
$token = $token.Replace('"', '').Trim()

$headers = @{
    Authorization    = "Bearer $token"
    'Notion-Version' = '2026-03-11'
    'Content-Type'   = 'application/json'
}

function Get-Children([string]$blockId) {
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
    # one-child block as an object instead of an array and break the snapshot's shape.
    return ,$all
}

function Get-Snapshot([string]$label) {
    $page = Invoke-RestMethod -Uri "https://api.notion.com/v1/pages/$FIXTURE_PAGE" -Headers $headers

    $childrenMap = [ordered]@{}
    $visited = [System.Collections.Generic.List[string]]::new()
    $visited.Add($FIXTURE_PAGE)

    for ($i = 0; $i -lt $visited.Count; $i++) {
        $id = $visited[$i]
        $blocks = Get-Children $id
        $childrenMap[$id] = $blocks
        foreach ($b in $blocks) {
            if ($b.has_children -and -not $visited.Contains($b.id)) { $visited.Add($b.id) }
        }
    }

    return [ordered]@{
        capturedFromPage = $FIXTURE_PAGE
        capturedAt       = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        notionVersion    = '2026-03-11'
        note             = "State of the fixture page with the identity-stability edits applied ($label). Generated by tools/verify-identity-stability.ps1; the page itself was restored afterwards."
        page             = $page
        children         = $childrenMap
    }
}

function Set-BlockText([string]$blockId, [string]$type, [string]$text) {
    $body = @{ $type = @{ rich_text = @(@{ type = 'text'; text = @{ content = $text } }) } } | ConvertTo-Json -Depth 10
    Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$blockId" -Method Patch -Headers $headers -Body $body | Out-Null
}

function Add-Paragraph([string]$parentId, [string]$text) {
    $body = @{ children = @(@{ object = 'block'; type = 'paragraph'; paragraph = @{ rich_text = @(@{ type = 'text'; text = @{ content = $text } }) } }) } | ConvertTo-Json -Depth 10
    $r = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$parentId/children" -Method Patch -Headers $headers -Body $body
    return ,$r.results[0].id
}

function Remove-Block([string]$blockId) {
    $body = @{ in_trash = $true } | ConvertTo-Json
    Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$blockId" -Method Patch -Headers $headers -Body $body | Out-Null
}

Write-Output 'Capturing the pre-edit state...'
$before = Get-Snapshot 'pre-edit'

$addedBlock = $null
try {
    Write-Output 'Edit 1: modifying one paragraph in place...'
    Set-BlockText -blockId $MODIFIED_BLOCK -type 'paragraph' -text $EDITED_TEXT

    Write-Output 'Edit 2: appending one paragraph at the end of the page...'
    $addedBlock = Add-Paragraph -parentId $FIXTURE_PAGE -text 'Paragraph added after the original capture, to prove that insertion moves no existing block.'

    Write-Output 'Edit 3: blanking a heading, which removes a whole section...'
    Set-BlockText -blockId $BLANKED_HEADING_BLOCK -type $BLANKED_HEADING_TYPE -text $BLANKED_TEXT

    Write-Output 'Capturing the edited state...'
    $edited = Get-Snapshot 'one paragraph modified, one paragraph appended, one heading blanked'

    New-Item -ItemType Directory -Force -Path $fixtureDir | Out-Null
    $edited | ConvertTo-Json -Depth 40 | Set-Content -Path $editedFile -Encoding UTF8
    Write-Output "Wrote $editedFile"
}
finally {
    Write-Output 'Restoring the page...'
    if ($addedBlock) { Remove-Block -blockId $addedBlock }
    Set-BlockText -blockId $MODIFIED_BLOCK -type 'paragraph' -text $ORIGINAL_TEXT
    Set-BlockText -blockId $BLANKED_HEADING_BLOCK -type $BLANKED_HEADING_TYPE -text $ORIGINAL_HEADING_TEXT
}

# Prove the restore worked, by comparing the real page against the pre-edit capture. Without this the
# script could leave the fixture page permanently altered and nobody would notice until the app behaved
# oddly on device.
Write-Output 'Verifying the page was restored...'
$after = Get-Snapshot 'post-restore'

$beforeIds = @($before.children[$FIXTURE_PAGE] | ForEach-Object { $_.id })
$afterIds = @($after.children[$FIXTURE_PAGE] | ForEach-Object { $_.id })

if (($beforeIds -join ',') -ne ($afterIds -join ',')) {
    throw "Restore FAILED: top-level block ids differ.`nBefore: $($beforeIds -join ',')`nAfter:  $($afterIds -join ',')"
}

function Assert-Text([string]$blockId, [string]$expected, [string]$what) {
    $node = $after.children[$FIXTURE_PAGE] | Where-Object { $_.id -eq $blockId }
    $actual = $node.($node.type).rich_text.plain_text -join ''
    if ($actual -ne $expected) {
        throw "Restore FAILED: $what is '$actual', expected '$expected'."
    }
}

Assert-Text -blockId $MODIFIED_BLOCK -expected $ORIGINAL_TEXT -what 'the modified paragraph'
Assert-Text -blockId $BLANKED_HEADING_BLOCK -expected $ORIGINAL_HEADING_TEXT -what 'the blanked heading'

# The evidence itself, printed so the run is self-documenting.
$editedIds = @($edited.children[$FIXTURE_PAGE] | ForEach-Object { $_.id })
$kept = @($beforeIds | Where-Object { $editedIds -contains $_ })
$added = @($editedIds | Where-Object { $beforeIds -notcontains $_ })

Write-Output ''
Write-Output 'Identity stability evidence:'
Write-Output ("  top-level blocks before edit  : {0}" -f $beforeIds.Count)
Write-Output ("  top-level blocks with the edit: {0}" -f $editedIds.Count)
Write-Output ("  ids that survived the edits   : {0} of {1}" -f $kept.Count, $beforeIds.Count)
Write-Output ("  ids added by the edits        : {0}" -f ($added -join ', '))
Write-Output ("  edited block id unchanged     : {0}" -f ($editedIds -contains $MODIFIED_BLOCK))
Write-Output ("  blanked heading id unchanged  : {0}" -f ($editedIds -contains $BLANKED_HEADING_BLOCK))
Write-Output ("  page restored exactly         : {0}" -f (($beforeIds -join ',') -eq ($afterIds -join ',')))
