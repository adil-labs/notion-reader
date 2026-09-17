#requires -Version 7.0
<#
.SYNOPSIS
    Seeds the Phase 0 Notion parser fixture page.

.DESCRIPTION
    Creates one Notion page that deliberately contains every block type the
    implementation spec's POC must support, plus the edge cases its test strategy
    calls out (fragmented rich text, a link, inline code, an empty heading, a long
    paragraph, nested children and one unsupported block).

    See docs/notion/notion-integration-implementation-spec.md (Phase 0 — Contract and fixture),
    docs/phase0-fixture-contract.md (the expected ReaderDocument for this page) and
    docs/decisions.md (the settled mapping rules the fixture was built against).

    Re-running this script creates a DUPLICATE page. It is a seeding tool, not a
    sync tool. The token is read from the repo-root .env and is never printed.

.EXAMPLE
    pwsh -File tools/seed-reader-fixture.ps1
    pwsh -File tools/seed-reader-fixture.ps1 -ParentPageId 3dec9345-e10a-812d-be61-fc24526f9fd3
#>
param(
    # Parent page for the fixture. Defaults to workspace level, which requires the
    # integration to have workspace-level insert capability; pass a shared page id
    # when that is not granted.
    [string]$ParentPageId = '',
    [string]$PageTitle = 'Reader Fixture — Block Type Coverage'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repoRoot '.env'

if (-not (Test-Path $envPath)) {
    throw "Cannot find $envPath. It must hold the Notion token (key=value or a bare value)."
}

# The token file holds a bare value with no KEY= prefix, so strip a prefix if present,
# then trim quotes. The value is never echoed.
$token = (Get-Content $envPath -Raw).Trim()
$token = $token -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', ''
$token = $token.Replace('"', '').Trim()

$headers = @{
    Authorization    = "Bearer $token"
    'Notion-Version' = '2026-03-11'
    'Content-Type'   = 'application/json'
}

function New-RichText {
    param(
        [string]$Text,
        [switch]$Bold,
        [switch]$Code,
        [string]$Link = ''
    )

    $rt = @{ type = 'text'; text = @{ content = $Text } }
    if ($Link) { $rt.text.link = @{ url = $Link } }

    if ($Bold -or $Code) {
        $rt.annotations = @{}
        if ($Bold) { $rt.annotations.bold = $true }
        if ($Code) { $rt.annotations.code = $true }
    }

    return $rt
}

function New-TextBlock {
    param([string]$Type, [array]$RichText, [hashtable]$Extra = @{})

    $payload = @{ rich_text = $RichText }
    foreach ($k in $Extra.Keys) { $payload[$k] = $Extra[$k] }

    return @{ type = $Type; $Type = $payload }
}

$LONG_PARAGRAPH = 'Long paragraph sentence one, deliberately written to be short. ' +
    'Long paragraph sentence two continues the thought without stopping. ' +
    'Long paragraph sentence three adds still more words to the block. ' +
    'Long paragraph sentence four carries the total well past three hundred characters. ' +
    'Long paragraph sentence five pushes it further still. ' +
    'Long paragraph sentence six ends the block.'

$children = @(
    # 1 — content before the first heading must land in an implicit intro section.
    (New-TextBlock -Type 'paragraph' -RichText @(
            (New-RichText -Text 'Intro paragraph before any heading.')
        ))

    # 2 — rich text split across three fragments must rejoin with no duplicated spacing.
    (New-TextBlock -Type 'paragraph' -RichText @(
            (New-RichText -Text 'Fragment one, '),
            (New-RichText -Text 'fragment two' -Bold),
            (New-RichText -Text ', and fragment three.')
        ))

    # 3 — a link keeps its display text and records the href as metadata.
    (New-TextBlock -Type 'paragraph' -RichText @(
            (New-RichText -Text 'See '),
            (New-RichText -Text 'the Notion docs' -Link 'https://developers.notion.com/reference/intro'),
            (New-RichText -Text ' for details.')
        ))

    # 4 — inline code is kept as text with a content-type hint.
    (New-TextBlock -Type 'paragraph' -RichText @(
            (New-RichText -Text 'Inline '),
            (New-RichText -Text 'code_span' -Code),
            (New-RichText -Text ' inside a paragraph.')
        ))

    # 5 — first heading closes the intro section and opens level 1.
    (New-TextBlock -Type 'heading_1' -RichText @((New-RichText -Text 'Primary Heading')))

    # 6
    (New-TextBlock -Type 'paragraph' -RichText @((New-RichText -Text 'Body under the primary heading.')))

    # 7 — level 2 section.
    (New-TextBlock -Type 'heading_2' -RichText @((New-RichText -Text 'Secondary Heading')))

    # 8
    (New-TextBlock -Type 'bulleted_list_item' -RichText @((New-RichText -Text 'First bullet')))

    # 9 — nested child under a bulleted list item.
    (New-TextBlock -Type 'bulleted_list_item' -RichText @((New-RichText -Text 'Second bullet')) -Extra @{
            children = @(
                (New-TextBlock -Type 'bulleted_list_item' -RichText @(
                        (New-RichText -Text 'Nested bullet under the second bullet.')
                    ))
            )
        })

    # 10 — numbered item with a nested bulleted child, mirroring the real workspace page.
    (New-TextBlock -Type 'numbered_list_item' -RichText @((New-RichText -Text 'First numbered item')) -Extra @{
            children = @(
                (New-TextBlock -Type 'bulleted_list_item' -RichText @(
                        (New-RichText -Text 'Nested bullet under the first numbered item.')
                    ))
            )
        })

    # 11
    (New-TextBlock -Type 'numbered_list_item' -RichText @((New-RichText -Text 'Second numbered item')))

    # 12 / 13 — to-do state must survive mapping.
    (New-TextBlock -Type 'to_do' -RichText @((New-RichText -Text 'Unchecked task')) -Extra @{ checked = $false })
    (New-TextBlock -Type 'to_do' -RichText @((New-RichText -Text 'Checked task')) -Extra @{ checked = $true })

    # 14
    (New-TextBlock -Type 'quote' -RichText @((New-RichText -Text 'Quoted text for the fixture.')))

    # 15
    (New-TextBlock -Type 'code' -RichText @((New-RichText -Text 'val answer = 42')) -Extra @{ language = 'kotlin' })

    # 16 — divider produces navigation structure but no spoken chunk.
    @{ type = 'divider'; divider = @{} }

    # 17 — level 3 section.
    (New-TextBlock -Type 'heading_3' -RichText @((New-RichText -Text 'Tertiary Heading')))

    # 18
    (New-TextBlock -Type 'paragraph' -RichText @((New-RichText -Text 'Body under the tertiary heading.')))

    # 19 — empty heading must not create an unusable navigation target.
    (New-TextBlock -Type 'heading_2' -RichText @())

    # 20
    (New-TextBlock -Type 'paragraph' -RichText @((New-RichText -Text 'Paragraph after the empty heading.')))

    # 21 — unsupported block must degrade locally, not fail the page.
    @{
        type  = 'image'
        image = @{
            type     = 'external'
            external = @{ url = 'https://example.com/fixture-image.png' }
            caption  = @((New-RichText -Text 'Unsupported image block'))
        }
    }

    # 22 — long paragraph needs sentence-boundary splitting.
    (New-TextBlock -Type 'paragraph' -RichText @((New-RichText -Text $LONG_PARAGRAPH)))

    # 23 — toggle is a container whose children sit in reading order.
    (New-TextBlock -Type 'toggle' -RichText @((New-RichText -Text 'Toggle summary')) -Extra @{
            children = @(
                (New-TextBlock -Type 'paragraph' -RichText @(
                        (New-RichText -Text 'Inside the toggle.')
                    ))
            )
        })
)

if ($ParentPageId) {
    $parent = @{ type = 'page_id'; page_id = $ParentPageId }
}
else {
    $parent = @{ type = 'workspace'; workspace = $true }
}

$body = @{
    parent     = $parent
    properties = @{ title = @{ title = @((New-RichText -Text $PageTitle)) } }
    children   = $children
} | ConvertTo-Json -Depth 40 -Compress

try {
    $page = Invoke-RestMethod -Uri 'https://api.notion.com/v1/pages' -Method Post -Headers $headers -Body $body
}
catch {
    $detail = $_.ErrorDetails.Message
    throw "Page creation failed. $detail"
}

Write-Output "Created fixture page."
Write-Output "id    = $($page.id)"
Write-Output "url   = $($page.url)"
Write-Output "blocks sent = $($children.Count)"
Write-Output ""
Write-Output "Next: verify with"
Write-Output "  pwsh -File tools/notion-tool.ps1 -Action children -Id $($page.id)"
