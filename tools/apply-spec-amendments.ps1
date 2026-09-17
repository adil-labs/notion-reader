#requires -Version 7.0
<#
.SYNOPSIS
    Applies the remaining outstanding amendments to the Notion Integration Implementation Spec.

.DESCRIPTION
    docs/decisions.md section 7 lists amendments the Integration spec needs. As of round 3 only three
    remain outstanding, and all three are corrections forced by facts verified against the live
    workspace rather than preferences:

      A1  section 7.5 drift   - ChunkMetadata is missing `headingLevel`, which the Note screen depends on
      A2  section 7.7         - ReaderLibraryEntry is missing `status`; record that projectIds may be
                                empty and that Status is schema-scoped rather than a global facet
      A3  section 7.8         - TASKS is recognized by schema (the live database is named "Todo List"),
                                and no adapter may claim KNOWLEDGE

    Three constraints shaped how this works, and they are worth stating because they are properties of
    the API rather than of this script:

    1. **This API version rejects the `after` parameter in both the body and the query**, so a block
       cannot be inserted at a position. Every amendment is therefore an in-place text edit of a block
       that already exists. Where an amendment needs to explain itself and cannot add a paragraph, the
       explanation goes into the code block as a comment - which is the right place for it anyway.
    2. **Patching replaces a block's whole rich_text**, so each amendment rewrites one block in full
       rather than splicing it. That is why the replacement text below restates the entire block.
    3. **A partially applied run cannot be resumed by text matching alone**, because the original text
       is gone once a block is edited. Each amendment therefore checks for its own "applied" marker
       first and reports `already applied` instead of failing.

    Dry run by default. Pass -Apply to write. Nothing is written without -Apply.

.EXAMPLE
    pwsh -File tools/apply-spec-amendments.ps1                  # dry run: show what would change
    pwsh -File tools/apply-spec-amendments.ps1 -Inspect         # dump the raw blocks it matched
    pwsh -File tools/apply-spec-amendments.ps1 -Apply           # write the amendments
    pwsh -File tools/apply-spec-amendments.ps1 -Only A3 -Apply  # write one amendment
#>
param(
    [switch]$Apply,
    [switch]$Inspect,
    [string]$Only = ''
)

$ErrorActionPreference = 'Stop'

$SPEC_PAGE = '3dec9345-e10a-81e8-8b28-e7d04672e53e'

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

# ---------------------------------------------------------------------------- amendment definitions

# A1 rewrites one block that holds the ENTIRE reader domain model, not just ChunkMetadata. Patching
# replaces a block's whole rich_text, so this must restate every declaration that block contains;
# sending only ChunkMetadata would silently delete ReaderDocument, ReaderSection and ReaderChunk from
# the governing spec. `-Inspect` exists precisely to make that visible before writing.
$newDomainModel = @'
data class ReaderDocument(
    val pageId: String,
    val title: String,
    val sections: List<ReaderSection>,
    val lastEditedAt: Instant?,
)

data class ReaderSection(
    val id: String,
    val title: String?,
    val level: Int,
    val chunks: List<ReaderChunk>,
)

data class ReaderChunk(
    val id: String,
    val sourceBlockId: String,
    val kind: ReaderChunkKind,
    val text: String,
    val metadata: ChunkMetadata = ChunkMetadata(),
)

data class ChunkMetadata(
    val checked: Boolean? = null,
    val language: String? = null,
    val linkTargets: List<String> = emptyList(),
    val hasInlineCode: Boolean = false,
    val headingLevel: Int? = null,   // heading_1..3 mapped to 1..3, so the Note screen can render
                                     // heading hierarchy from the chunk itself rather than guessing
                                     // it back out of the enclosing section
)

enum class ReaderChunkKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TODO,
    QUOTE,
    CODE,
}
'@

$newLibraryEntry = @'
data class ReaderLibraryEntry(
    val pageId: String,
    val title: String,
    val kind: ReaderEntryKind,
    val area: String? = null,
    val topics: Set<String> = emptySet(),
    val date: LocalDate? = null,
    val summary: String? = null,
    val projectIds: Set<String> = emptySet(),
    val status: String? = null,
    val sourceSchema: ReaderSourceSchema = ReaderSourceSchema.GENERIC,
)

enum class ReaderEntryKind {
    KNOWLEDGE,   // no accessible source backs this yet; no adapter may claim it
    REFLECTION,
    PRACTICE,
    PROJECT,
    TASK,
    GENERIC,
}

enum class ReaderSourceSchema {
    KNOWLEDGE,   // as above
    REFLECTIONS,
    PRACTICE_AND_LEARNING,
    PROJECTS,
    TASKS,
    GENERIC,
}
'@

$libraryNotesParagraph = 'ReaderLibraryEntry is for discovery and queue construction. ReaderDocument is for linear reading. Do not require every metadata field before a document can be read. projectIds may be empty: no accessible data source currently exposes a relation property, so an adapter must not treat an empty set as an error. status carries the source schema''s own user-facing value rather than a universal enum, because the vocabularies differ per schema (six values in Projects, three in Todo List) and overlap only partially. A raw string is therefore not a cross-schema facet, so a Status filter is schema-scoped in the first implementation: it filters within a Type selection rather than across all content. A workspace-wide Status facet would be the point at which a normalized enum becomes justified.'

$adapterParagraph = 'Do not hard-code playback logic to a database name. The adapter recognizes a schema and produces normalized metadata; playback consumes only reader-domain models. Recognition itself must be schema-based rather than name-based: the TASKS source is the live database named "Todo List", so an adapter matching on the name would miss it. Discriminate on its schema instead - the title property is Task name, together with Status and Due date. No adapter may claim KNOWLEDGE, because no accessible source backs it; the member remains a defined future kind that nothing may assume exists. Skills and Inbox are readable but are not recognized schemas, so they fall back to GENERIC rather than acquiring a Kind or SourceSchema value merely because the databases exist.'

$amendments = @(
    [pscustomobject]@{
        Id       = 'A1'
        Section  = '7.5 # Reader domain model'
        Summary  = 'Add ChunkMetadata.headingLevel (section 7.5 drift; the applied shape is missing one field)'
        Kind     = 'code'
        Language = 'kotlin'
        Locate   = 'data class ChunkMetadata('
        Applied  = 'val headingLevel'
        NewText  = $newDomainModel
    },
    [pscustomobject]@{
        Id       = 'A2'
        Section  = '7.7 ## Normalized library metadata'
        Summary  = 'Add ReaderLibraryEntry.status, and record the projectIds and schema-scoped Status caveats'
        Kind     = 'code'
        Language = 'kotlin'
        Locate   = 'val projectIds: Set<String> = emptySet(),'
        Applied  = 'val status: String? = null,'
        NewText  = $newLibraryEntry
    },
    [pscustomobject]@{
        Id       = 'A2b'
        Section  = '7.7 ## Normalized library metadata'
        Summary  = 'Explain projectIds being empty and Status being schema-scoped'
        Kind     = 'paragraph'
        Locate   = 'ReaderLibraryEntry is for discovery and queue construction.'
        Applied  = 'projectIds may be empty'
        NewText  = $libraryNotesParagraph
    },
    [pscustomobject]@{
        Id       = 'A3'
        Section  = '7.8 ## Workspace schema adapters'
        Summary  = 'Record TASK-by-schema and that no adapter claims KNOWLEDGE'
        Kind     = 'paragraph'
        Locate   = 'Do not hard-code playback logic to a database name.'
        Applied  = 'Recognition itself must be schema-based'
        NewText  = $adapterParagraph
    }
)

if ($Only) {
    $amendments = @($amendments | Where-Object { $_.Id -eq $Only })
    if ($amendments.Count -eq 0) { throw "No amendment with -Id '$Only'." }
}

# ---------------------------------------------------------------------------- helpers

function Get-PageBlocks {
    $all = @()
    $cursor = $null
    do {
        $url = "https://api.notion.com/v1/blocks/$SPEC_PAGE/children?page_size=100"
        if ($cursor) { $url += "&start_cursor=$cursor" }
        $r = Invoke-RestMethod -Uri $url -Headers $headers
        $all += $r.results
        $cursor = $r.next_cursor
    } while ($r.has_more)
    # Leading comma: PowerShell unwraps a single-element array on return.
    return ,$all
}

function Get-BlockText($block) {
    $payload = $block.PSObject.Properties[$block.type].Value
    if ($null -eq $payload) { return '' }
    $rt = $payload.PSObject.Properties['rich_text']
    if ($null -eq $rt -or $null -eq $rt.Value) { return '' }
    return ($rt.Value | ForEach-Object { $_.plain_text }) -join ''
}

function Set-BlockText($block, $amendment) {
    $richText = @(@{ type = 'text'; text = @{ content = $amendment.NewText } })

    $payload = if ($amendment.Kind -eq 'code') {
        @{ rich_text = $richText; language = $amendment.Language }
    }
    else {
        @{ rich_text = $richText }
    }

    $body = @{ $block.type = $payload } | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($block.id)" -Method Patch -Headers $headers -Body $body | Out-Null
}

# ---------------------------------------------------------------------------- run

Write-Output "Fetching the Integration spec page ($SPEC_PAGE)..."
$blocks = Get-PageBlocks
Write-Output "Blocks on page: $($blocks.Count)"
Write-Output ''

if ($Inspect) {
    foreach ($a in $amendments) {
        $match = $blocks | Where-Object { (Get-BlockText $_) -like "*$($a.Locate)*" } | Select-Object -First 1
        Write-Output "--- $($a.Id) ($($a.Section))"
        if (-not $match) { Write-Output '  NO MATCH'; continue }
        Write-Output "  block id : $($match.id)"
        Write-Output "  type     : $($match.type)"
        $text = Get-BlockText $match
        Write-Output "  length   : $($text.Length)"
        Write-Output '  ----- full current text -----'
        # Printed in full: patching replaces a block's entire rich_text, so the replacement must
        # restate everything this block currently holds, not just the part being amended.
        Write-Output $text
        Write-Output '  -----------------------------'
        Write-Output ''
    }
    return
}

$applied = 0
$skipped = 0
$failed = @()

foreach ($a in $amendments) {
    $match = $blocks | Where-Object { (Get-BlockText $_) -like "*$($a.Locate)*" } | Select-Object -First 1

    if (-not $match) {
        Write-Output "[$($a.Id)] NOT FOUND  - could not locate a block containing '$($a.Locate)'"
        $failed += $a.Id
        continue
    }

    $current = Get-BlockText $match

    if ($current -like "*$($a.Applied)*") {
        Write-Output "[$($a.Id)] already applied  - $($a.Section)"
        $skipped++
        continue
    }

    if (-not $Apply) {
        Write-Output "[$($a.Id)] WOULD CHANGE  $($match.type) $($match.id)"
        Write-Output "        $($a.Summary)"
        Write-Output "        $($a.Section)"
        Write-Output "        current length $($current.Length) -> new length $($a.NewText.Length)"
        Write-Output ''
        continue
    }

    Set-BlockText $match $a
    Write-Output "[$($a.Id)] APPLIED  $($a.Section)  $($a.Summary)"
    $applied++
}

Write-Output ''
if ($Apply) {
    Write-Output "applied=$applied  already-applied=$skipped  not-found=$($failed.Count)"
}
else {
    Write-Output 'DRY RUN: nothing was written. Pass -Apply to write these amendments.'
}
if ($failed.Count -gt 0) { throw "Amendments not found: $($failed -join ', ')" }
