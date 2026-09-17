#requires -Version 7.0
<#
.SYNOPSIS
    Small read-only Notion inspector used while developing the integration layer.

.DESCRIPTION
    Talks to the Notion REST API with the integration token from the repo-root .env.
    Useful for answering "what does the app actually receive?", which is how the
    fixtures and expected ReaderDocument contracts in docs/ were derived.

    Read-only: it never creates, updates or deletes anything.

.EXAMPLE
    pwsh -File tools/notion-tool.ps1 -Action list
    pwsh -File tools/notion-tool.ps1 -Action children -Id <page-or-block-id>
    pwsh -File tools/notion-tool.ps1 -Action page -Id <page-id>
#>
param(
    [ValidateSet('list', 'children', 'page')]
    [string]$Action = 'list',
    [string]$Id = '',
    [string]$Query = ''
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

function Get-Title($item) {
    $t = ''
    if ($item.object -eq 'page') {
        $t = ($item.properties.PSObject.Properties |
            Where-Object { $_.Value.type -eq 'title' } |
            ForEach-Object { $_.Value.title } |
            ForEach-Object { $_.plain_text }) -join ''
    }
    elseif ($item.PSObject.Properties.Name -contains 'title') {
        $t = ($item.title | ForEach-Object { $_.plain_text }) -join ''
    }
    if (-not $t) { $t = '(untitled)' }
    return $t
}

function Get-RichText($block) {
    $payload = $block.PSObject.Properties[$block.type].Value
    if ($null -eq $payload) { return '' }
    $rt = $payload.PSObject.Properties['rich_text']
    if ($null -eq $rt -or $null -eq $rt.Value) { return '' }
    return ($rt.Value | ForEach-Object { $_.plain_text }) -join ''
}

switch ($Action) {
    'list' {
        $cursor = $null
        $all = @()
        do {
            $payload = @{ query = $Query; page_size = 100 }
            if ($cursor) { $payload.start_cursor = $cursor }
            $body = $payload | ConvertTo-Json -Compress
            $r = Invoke-RestMethod -Uri 'https://api.notion.com/v1/search' -Method Post -Headers $headers -Body $body
            $all += $r.results
            $cursor = $r.next_cursor
        } while ($r.has_more)

        Write-Output "RESULTS=$($all.Count)"
        # Most recently edited first, so newly created planning docs are obvious.
        foreach ($item in ($all | Sort-Object last_edited_time -Descending)) {
            Write-Output ("{0}`t{1}`t{2}`t{3}" -f $item.last_edited_time, $item.object, $item.id, (Get-Title $item))
        }
    }

    'page' {
        if (-not $Id) { throw '-Id is required for -Action page.' }
        $p = Invoke-RestMethod -Uri "https://api.notion.com/v1/pages/$Id" -Method Get -Headers $headers
        Write-Output "id          = $($p.id)"
        Write-Output "url         = $($p.url)"
        Write-Output "last_edited = $($p.last_edited_time)"
        Write-Output "title       = $(Get-Title $p)"
    }

    'children' {
        if (-not $Id) { throw '-Id is required for -Action children.' }
        $cursor = $null
        $n = 0
        do {
            $url = "https://api.notion.com/v1/blocks/$Id/children?page_size=100"
            if ($cursor) { $url += "&start_cursor=$cursor" }
            $r = Invoke-RestMethod -Uri $url -Method Get -Headers $headers

            foreach ($b in $r.results) {
                $n++
                $text = Get-RichText $b
                $flag = ''
                if ($b.has_children) { $flag = 'CHILDREN' }
                Write-Output ("{0,3}  {1,-22} {2,-9} {3}`t{4}" -f $n, $b.type, $flag, $b.id, $text)
                if ($b.has_children) {
                    $inner = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($b.id)/children?page_size=100" -Method Get -Headers $headers
                    foreach ($c in $inner.results) {
                        Write-Output ("       ^-- {0,-18} {1}`t{2}" -f $c.type, $c.id, (Get-RichText $c))
                    }
                }
            }
            $cursor = $r.next_cursor
        } while ($r.has_more)
        Write-Output "TOTAL=$n"
    }
}
