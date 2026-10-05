#requires -Version 7.0
<#
.SYNOPSIS
Applies the approved Explore catalog and cached-index refresh amendment to the
authoritative UI Screens & Navigation Contract in Notion.

.DESCRIPTION
The Notion API replaces a block's complete rich-text payload.  This script is
dry-run by default and targets stable block IDs after verifying the current
text, so a changed source document cannot be silently overwritten.
#>
param([switch]$Apply)

$ErrorActionPreference = 'Stop'

$pageId = '3dec9345-e10a-81b6-9cd2-d54532ad6fcd'
$token = (Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) '.env') -Raw).Trim()
$headers = @{
    Authorization = "Bearer $token"
    'Notion-Version' = '2026-03-11'
    'Content-Type' = 'application/json'
}

$changes = @(
    [pscustomobject]@{ Id = 'a1a10479-a08f-4869-8ec7-810d86664a0e'; Type = 'code'; Expected = '└── Explore → pre-filtered Browse'; Text = @'
App
│
├── Home
│   ├── Continue listening → Player
│   ├── Your queues → Queue Details
│   └── Explore preview
│       ├── Area or Topic → pre-filtered Browse
│       └── See all Explore → Explore Catalog
│
├── Browse
│   ├── Search
│   ├── Filters
│   ├── Sorting
│   ├── Result → Note
│   ├── Play all → Player
│   ├── Add all to queue → Queue Destination
│   └── Browse Notion structure → Raw Notion Navigation
│
├── Queues
│   ├── Listening
│   │   ├── Continue listening
│   │   └── Recently completed
│   ├── Saved
│   │   └── Saved Queue Details
│   └── Smart
│       └── Smart Queue Details
│
└── Settings

Secondary screens:

Explore Catalog
Note
Player
Queue Destination
Create Saved Queue
Saved Queue Details
Edit Saved Queue
Smart Queue Details
Edit Smart Queue
Raw Notion Navigation
Raw Notion Database Pages
'@ }
    [pscustomobject]@{ Id = '43198f5d-ef41-4ef2-9368-dba25d68e9b7'; Type = 'paragraph'; Expected = 'Explore contains deterministic discovery shortcuts into Browse.'; Text = 'Home contains a bounded, deterministic Explore preview. It is a set of discovery shortcuts into Browse, not the complete metadata taxonomy.' }
    [pscustomobject]@{ Id = '128daaa4-6e44-4136-870b-9f53ec87555b'; Type = 'code'; Expected = 'Android TTS'; Text = @'
Explore

Chess
11 notes

Mind & Learning
8 notes

Business & Strategy
4 notes

See all Explore
'@ }
    [pscustomobject]@{ Id = '2a9ca5b2-118a-4b93-9e4e-5e1ba92e5c49'; Type = 'paragraph'; Expected = 'Explore entries are reader-oriented shortcuts into result sets.'; Text = 'Each preview entry is a reader-oriented shortcut into a result set. Selecting See all Explore opens the Explore Catalog.' }
    [pscustomobject]@{ Id = 'd2b6c369-5d69-42b0-83fc-319140ab2138'; Type = 'heading_2'; Expected = 'Explore selection'; Text = 'Explore Catalog' }
    [pscustomobject]@{ Id = '70cc5617-ea2e-4cae-b982-fde3f3301166'; Type = 'paragraph'; Expected = 'Initial Explore choices should be generated transparently from metadata rather than opaque AI recommendations.'; Text = 'The Explore Catalog is the complete deterministic entry point into workspace discovery. It lists every Area and Topic that occurs on at least one accessible Note, with its note count.' }
    [pscustomobject]@{ Id = '149a004b-6890-4649-8200-2d012792fadb'; Type = 'paragraph'; Expected = 'Useful signals may include:'; Text = 'Catalog order and selection are intentionally simple:' }
    [pscustomobject]@{ Id = '8208e6c6-fb0f-4c54-9f69-77f09065eb37'; Type = 'bulleted_list_item'; Expected = 'recently accessed Areas or Topics;'; Text = 'Areas appear before Topics, so every populated Area remains discoverable even when one Area has many Topics;' }
    [pscustomobject]@{ Id = 'c8874caa-e19d-408c-ae51-d82b3557b669'; Type = 'bulleted_list_item'; Expected = 'recently updated content;'; Text = 'each section is ordered alphabetically using a case-insensitive deterministic comparison;' }
    [pscustomobject]@{ Id = '4420532b-6ae8-4b07-b00b-8d63816677eb'; Type = 'bulleted_list_item'; Expected = 'Areas or Topics containing enough useful Notes.'; Text = 'selecting an Area opens Browse with that Area facet, and selecting a Topic opens Browse with that Topic facet.' }
    [pscustomobject]@{ Id = '4c2dc631-2e91-4a57-b6b9-102eeffdfd3a'; Type = 'paragraph'; Expected = 'The product must not require inferred emotional or personal-state recommendations to populate this section.'; Text = 'The catalog must not use opaque AI recommendations or a shared popularity cut-off. Home preview selection may be ranked separately, but it must link to this complete catalog.' }
    [pscustomobject]@{ Id = '3c6451ac-1b78-45af-8fd3-80c1183c4a32'; Type = 'code'; Expected = '→ filtered entry points into Browse'; Text = @'
Home

Continue listening
→ resumable Active Queue snapshots

Your queues
→ Saved and Smart Queue definitions

Explore preview
→ filtered entry points into Browse
→ Explore Catalog for the complete Area and Topic taxonomy
'@ }
    [pscustomobject]@{ Id = '901618fc-df34-42ad-ae0b-8bdd87e659a1'; Type = 'numbered_list_item'; Expected = 'Home contains Continue listening'; Text = 'Home contains Continue listening, Your queues, and a bounded Explore preview; Explore Catalog is a secondary destination.' }
    [pscustomobject]@{ Id = 'd788c443-7ef9-47fa-9606-afa08f6433a2'; Type = 'numbered_list_item'; Expected = 'Home Explore entries open pre-filtered Browse'; Text = 'Explore preview and catalog entries open pre-filtered Browse rather than creating persistent queues; the catalog exposes every populated Area and Topic.' }
    [pscustomobject]@{ Id = '3bb6f020-e151-48a7-a1c1-25a17567f190'; Type = 'paragraph'; Expected = 'Screens dependent on remote content must distinguish loading from empty states.'; Text = 'Screens dependent on remote content must distinguish loading from empty states. When a persisted Browse index exists, Browse, Home Explore, and Explore Catalog render that cached data immediately while a foreground refresh runs; a refresh failure retains the cached data rather than blanking the discovery surfaces.' }
)

function Get-BlockText($block) {
    $payload = $block.PSObject.Properties[$block.type].Value
    return ($payload.rich_text | ForEach-Object { $_.plain_text }) -join ''
}

foreach ($change in $changes) {
    $block = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($change.Id)" -Headers $headers
    if ($block.type -ne $change.Type) { throw "Block $($change.Id) changed type from $($change.Type) to $($block.type)." }
    $current = Get-BlockText $block
    if ($current -eq $change.Text) {
        Write-Output "[$($change.Id)] already applied"
        continue
    }
    if (-not $current.Contains($change.Expected)) { throw "Block $($change.Id) no longer contains its expected text." }
    if (-not $Apply) {
        Write-Output "[$($change.Id)] WOULD CHANGE $($change.Type)"
        continue
    }
    $payload = @{ $change.Type = @{ rich_text = @(@{ type = 'text'; text = @{ content = $change.Text } }) } }
    if ($change.Type -eq 'code') { $payload[$change.Type].language = $block.code.language }
    Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($change.Id)" -Method Patch -Headers $headers -Body ($payload | ConvertTo-Json -Depth 10) | Out-Null
    Write-Output "[$($change.Id)] applied"
}
