#requires -Version 7.0
<#
.SYNOPSIS
    Exports Notion pages to Markdown so the governing specs live in the repo.

.DESCRIPTION
    The Notion Reader contract set is authored in Notion. Coding agents need it locally,
    reviewable in a diff, so this exporter mirrors pages into docs/notion/.

    Notion remains authoritative. The exported file records the source page id and the
    page's last-edited time, so a stale mirror is detectable rather than silent.

    Read-only: it never writes to Notion.

.EXAMPLE
    pwsh -File tools/export-notion-doc.ps1 -All
    pwsh -File tools/export-notion-doc.ps1 -Id 3dec9345-e10a-81b6-9cd2-d54532ad6fcd -OutFile docs/notion/ui.md
#>
param(
    [string]$Id = '',
    [string]$OutFile = '',
    [switch]$All
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

$fence = ([char]96).ToString() * 3

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
    return $all
}

function Get-PlainText($block) {
    $payload = $block.PSObject.Properties[$block.type].Value
    if ($null -eq $payload) { return '' }
    $rt = $payload.PSObject.Properties['rich_text']
    if ($null -eq $rt -or $null -eq $rt.Value) { return '' }
    return ($rt.Value | ForEach-Object { $_.plain_text }) -join ''
}

function Get-MaxDepth($blockId, $depth) {
    if ($depth -gt 6) { return $depth }
    $max = $depth
    foreach ($b in (Get-Children $blockId)) {
        if ($b.has_children) {
            $d = Get-MaxDepth $b.id ($depth + 1)
            if ($d -gt $max) { $max = $d }
        }
    }
    return $max
}

function Convert-Blocks($blockId, $depth, $sb) {
    $indent = '  ' * $depth

    foreach ($b in (Get-Children $blockId)) {
        $text = Get-PlainText $b

        switch ($b.type) {
            'heading_1' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("# $text");
            }
            'heading_2' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("## $text");
            }
            'heading_3' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("### $text");
            }
            'paragraph' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("$indent$text");
            }
            'bulleted_list_item' {
                [void]$sb.AppendLine("$indent- $text");
            }
            'numbered_list_item' {
                # ${indent} needs braces: "$indent1. " parses as the undefined variable $indent1,
                # which silently drops the number and emits ". text".
                [void]$sb.AppendLine("${indent}1. $text");
            }
            'to_do' {
                $box = ' ';
                if ($b.to_do.checked) { $box = 'x' }
                [void]$sb.AppendLine("$indent- [$box] $text");
            }
            'quote' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("$indent> $text");
            }
            'callout' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("$indent> $text");
            }
            'code' {
                $lang = ''
                if ($b.code.PSObject.Properties['language']) { $lang = $b.code.language }
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("$indent$fence$lang");
                foreach ($line in ($text -split "`n")) { [void]$sb.AppendLine($line) }
                [void]$sb.AppendLine("$indent$fence");
            }
            'divider' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine('---');
            }
            'toggle' {
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("$indent<details>");
                [void]$sb.AppendLine("$indent<summary>$text</summary>");
                [void]$sb.AppendLine();
            }
            'image' {
                $url = ''
                $kind = $b.image.PSObject.Properties['type'].Value
                $url = $b.image.PSObject.Properties[$kind].Value.url
                [void]$sb.AppendLine("$indent![$text]($url)")
            }
            'child_page' {
                [void]$sb.AppendLine("$indent- [child page] $($b.child_page.title)")
            }
            'child_database' {
                [void]$sb.AppendLine("$indent- [child database] $($b.child_database.title)")
            }
            'bookmark' {
                [void]$sb.AppendLine("$indent- [bookmark] $($b.bookmark.url)")
            }
            default {
                [void]$sb.AppendLine("$indent<!-- unsupported block type: $($b.type) -->")
            }
        }

        if ($b.has_children -and $b.type -ne 'code') {
            if ($b.type -eq 'toggle') {
                Convert-Blocks $b.id ($depth + 1) $sb
                [void]$sb.AppendLine();
                [void]$sb.AppendLine("$indent</details>")
            }
            elseif ($b.type -in @('bulleted_list_item', 'numbered_list_item', 'to_do')) {
                Convert-Blocks $b.id ($depth + 1) $sb
            }
            else {
                Convert-Blocks $b.id $depth $sb
            }
        }
    }
}

function Export-Doc([string]$pageId, [string]$outPath, [string]$label) {
    $page = Invoke-RestMethod -Uri "https://api.notion.com/v1/pages/$pageId" -Headers $headers

    $title = ''
    foreach ($p in $page.properties.PSObject.Properties) {
        if ($p.Value.type -eq 'title') {
            $title = ($p.Value.title | ForEach-Object { $_.plain_text }) -join ''
            break
        }
    }

    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.AppendLine("<!--")
    [void]$sb.AppendLine("  GENERATED FILE - do not edit by hand.")
    [void]$sb.AppendLine("  Exported from Notion by tools/export-notion-doc.ps1.")
    [void]$sb.AppendLine("  Source page: $title")
    [void]$sb.AppendLine("  Page id:     $pageId")
    [void]$sb.AppendLine("  Notion URL:  $($page.url)")
    [void]$sb.AppendLine("  Notion last edited: $($page.last_edited_time)")
    [void]$sb.AppendLine("  Notion is authoritative. Re-run the exporter after editing there.")
    [void]$sb.AppendLine("-->")
    [void]$sb.AppendLine()
    [void]$sb.AppendLine("# $title")
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('> Governing document, mirrored from Notion. See the header above for provenance.')
    [void]$sb.AppendLine()

    Convert-Blocks $pageId 0 $sb

    $full = Join-Path $repoRoot $outPath
    $dir = Split-Path -Parent $full
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    # Collapse runs of blank lines so the output has stable, reviewable diffs.
    $clean = ($sb.ToString() -split "`r?`n") -join "`n"
    $clean = $clean -replace "(`n){3,}", "`n`n"
    $clean.TrimEnd() + "`n" | Set-Content -Path $full -Encoding UTF8

    $lines = (Get-Content $full).Count
    Write-Output "$label -> $outPath ($lines lines)"
}

$docs = [ordered]@{
    '3dec9345-e10a-81ab-aa68-cf0b2ee17e45' = @('docs/notion/product-and-architecture-contract.md', 'product-and-architecture-contract')
    '3dec9345-e10a-81b6-9cd2-d54532ad6fcd' = @('docs/notion/ui-screens-and-navigation-contract.md', 'ui-screens-and-navigation-contract')
    '3dec9345-e10a-81e8-8b28-e7d04672e53e' = @('docs/notion/notion-integration-implementation-spec.md', 'notion-integration-implementation-spec')
    '3dec9345-e10a-812d-be61-fc24526f9fd3' = @('docs/notion/notion-note-organization.md', 'notion-note-organization')
}

if ($All) {
    foreach ($k in $docs.Keys) {
        Export-Doc $k $docs[$k][0] $docs[$k][1]
    }
}
elseif ($Id -and $OutFile) {
    Export-Doc $Id $OutFile 'doc'
}
else {
    throw 'Pass -All, or -Id and -OutFile.'
}
