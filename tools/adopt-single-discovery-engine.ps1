#requires -Version 7.0
<# Applies the user-authorized sole-engine decision to live governing sources. Dry-run by default. #>
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$credential = (Get-Content (Join-Path $repo '.env') -Raw).Trim()
$credential = ($credential -replace '(?i)^NOTION_(API_)?(TOKEN|KEY|SECRET)\s*=\s*', '').Replace('"', '').Trim()
$headers = @{ Authorization = "Bearer $credential"; 'Notion-Version' = '2026-03-11'; 'Content-Type' = 'application/json' }
$backup = Join-Path $repo ('build/single-engine-amendment/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$null = New-Item -ItemType Directory -Force $backup
$pages = @('3dec9345-e10a-81ab-aa68-cf0b2ee17e45','3dec9345-e10a-81b6-9cd2-d54532ad6fcd','3dec9345-e10a-81e8-8b28-e7d04672e53e')
function Read-Blocks($id) {
    $items = @(); $cursor = $null
    do {
        $uri = "https://api.notion.com/v1/blocks/$id/children?page_size=100"
        if ($cursor) { $uri += "&start_cursor=$cursor" }
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        $items += $response.results; $cursor = $response.next_cursor
    } while ($response.has_more)
    return $items
}
function Text-Of($block) { return (($block.($block.type).rich_text | ForEach-Object plain_text) -join '') }
function Rich-Text([string]$text) {
    $items = @()
    for ($offset=0; $offset -lt $text.Length; $offset+=1900) {
        $items += @{ type='text'; text=@{content=$text.Substring($offset,[Math]::Min(1900,$text.Length-$offset))} }
    }
    return ,$items
}
$pairs = @(
    @('In the release baseline, classification annotates eligible existing pairs without bypassing body-evidence eligibility or admission thresholds. The replacement retrieves explicit references independently as specified below.', 'Named references are retrieved independently of semantic similarity, while Similar passages require eligible thematic body evidence.'),
    @('Bounded candidate selection must distribute opportunities across Notes instead of allowing one long Note to consume the anchor budget.', 'Bounded work slices distribute opportunities across Notes, sections and passages without an anchor-admission or topic-count ceiling.'),
    @('The replacement engine uses the following automatic topic formation and publication requirements. Legacy baseline anchor limits and publication minima do not apply to this engine;', 'The sole section-aware hierarchical Discovery engine uses the following formation and publication requirements;'),
    @('Before general release enablement of the replacement engine, measure', 'Android performance acceptance requires measuring'),
    @('MiniLM and the experimental flag remain; failed or unmeasured gates block general release enablement.', 'MiniLM remains the embedding pipeline. Failed or unmeasured gates remain explicit production-readiness failures; they do not authorize retaining or executing a different topic algorithm.'),
    @('Independent retrieval uses the experimental engine flag. General release enablement requires the governing gates; the authorized debug trial is permitted as described below. The release baseline retains classification of already eligible pairs.', 'Independent retrieval is part of the sole Discovery engine in both debug and release builds. There is no engine-selection flag or fallback algorithm. Quality and Android performance acceptance are reported separately.'),
    @('Apply the Product contract quality and physical Android acceptance gates before general release enablement of the replacement engine;', 'Measure and report the Product contract quality and physical Android acceptance gates for the sole engine;'),
    @('In the release baseline, classify existing eligible pairs with a pure, versioned presentation policy:', 'Independently retrieve and classify explicit references with a pure, versioned evidence policy:'),
    @('an explicit instruction or citation in a current body passage naming', 'an explicit instruction or citation in a current non-heading referencing passage naming'),
    @('In the replacement engine, retrieve', 'In the sole Discovery engine, retrieve'),
    @('in the replacement engine.', 'in the sole Discovery engine.'),
    @('Developer evaluation and authorized debug trial', 'Developer evaluation and acceptance reporting')
)
$decision = 'All debug and release builds use one section-aware, hierarchical, evidence-backed Discovery engine. Retire the streaming64anchors algorithm, legacy topic builder, engine-selection flag and build property, switching branches and legacy-specific active checkpoints. Historical results may remain as documentation; no legacy algorithm executes, including during migration. The optional pinned MiniLM ONNX pipeline, local privacy, saved interests, source embeddings and listening behavior remain. This architectural decision does not declare quality, memory, playback or human TalkBack acceptance passed; report measured failures and unmeasured gates independently.'
$stageHeading = 'Unified Discovery stages and safe upgrade'
$stageText = @(
    'The sole engine separates indexing, candidate subject formation, passage-level membership projection, evidence validation, naming, publication, independent connection retrieval and persistence/UI projection. Every eligible cached section and passage eventually receives opportunity, including late subjects and long multi-topic sections. Preserve broad, focused, overlapping and single-Note Emerging subjects without an arbitrary topic-count limit. Validate semantic body membership independently of naming. A specific literal label from a representative eligible source may name a subject without occurring in every member; missing a common literal phrase must not erase valid semantic evidence. Retain valid unnamed candidates and their provenance while withholding unsupported labels. Named references remain independent of similarity; semantic connection opportunities extend beyond the first three sections.',
    'Upgrade recognizes legacy topic/checkpoint formats and removes incompatible derived clustering state without executing the legacy algorithm. Reuse embeddings only when model, tokenizer and current source identities match. Rebuild solely from eligible cached evidence, reconciling opaque topic identity only when genuine surviving passage evidence supports continuity; never retain misleading membership merely to preserve an ID. Previously hierarchical progress resumes only when source/model/policy/progress invariants match, otherwise restart safely without skipped evidence. Migration and replacement publication must recover idempotently from interruption, corruption and insufficient storage and expose only atomic consistent generations.',
    'Explicitly saved interest IDs, names and matching text survive derived-state migration, including interests whose old origin topic becomes unavailable. Preserve saved listening state, queues and unrelated settings. Quality acceptance still requires independent held-out judgments under the existing recall, precision, false-publication and supported-label targets. Android acceptance still requires at least ten thousand passages across more than two thousand uneven sections, at most one hundred twenty seconds active topic processing, at most one hundred twenty-eight MiB added Java/native memory over model-loaded idle, cancellation within one second, complete durable resume, at most sixty-four MiB combined derived storage, warm-search p95 below two seconds and no attributable playback failure or state mutation. Diverse representative workloads are required; repeated identical-vector synthetic workloads alone cannot establish these gates.'
)
$plan = @()
foreach ($page in $pages) {
    $blocks = @(Read-Blocks $page)
    $blocks | ConvertTo-Json -Depth 100 | Set-Content (Join-Path $backup "$page.json") -Encoding utf8
    foreach ($block in $blocks) {
        if ($block.type -notin @('paragraph','heading_1','heading_2','heading_3')) { continue }
        $before = Text-Of $block; $after = $before
        if ($before.StartsWith('The user has authorized a debug-build trial of the implemented replacement Discovery engine')) { $after = $decision }
        else { foreach ($pair in $pairs) { $after = $after.Replace($pair[0],$pair[1]) } }
        if ($after -cne $before) { $plan += @{page=$page;id=$block.id;type=$block.type;before=$before;after=$after} }
    }
    if (@($blocks | Where-Object { (Text-Of $_) -ceq $stageHeading }).Count -eq 0) {
        $plan += @{page=$page;append=$true}
    }
}
$plan | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $backup 'plan.json') -Encoding utf8
Write-Output "Review plan: $backup/plan.json; planned actions=$($plan.Count)"
if (-not $Apply) { return }
foreach ($action in $plan) {
    if ($action.append) {
        $children = @(@{object='block';type='heading_1';heading_1=@{rich_text=(Rich-Text $stageHeading)}})
        $children += @($stageText | ForEach-Object { @{object='block';type='paragraph';paragraph=@{rich_text=(Rich-Text $_)}} })
        $body = @{children=$children} | ConvertTo-Json -Depth 20
        $null = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($action.page)/children" -Method Patch -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body))
    } else {
        $live = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($action.id)" -Headers $headers
        if ((Text-Of $live) -cne $action.before) { throw 'Live source changed since inspection' }
        $body = @{$action.type=@{rich_text=(Rich-Text $action.after)}} | ConvertTo-Json -Depth 20
        $null = Invoke-RestMethod -Uri "https://api.notion.com/v1/blocks/$($action.id)" -Method Patch -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body))
    }
}
