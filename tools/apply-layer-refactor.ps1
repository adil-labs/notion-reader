<#
.SYNOPSIS
    Moves the app's sources into explicit architectural layers, and rewrites every reference.

.DESCRIPTION
    The Product & Architecture Contract declares a layered architecture and states boundary rules, but
    the code did not express them. Measured from the source tree, the internal import graph contained two
    cycles and an adapter living in the application package:

        application -> ui     (ReaderApp imported ui.ContinueListeningCard)
        ui -> application     (BrowseScreen imported app.BrowseIndexStatus, HomeScreen app.describeLastActivity)
        reader -> notion      (ReaderDocumentMapper imported notion.NormalizedBlock)
        notion -> reader      (NotionApi imported reader.ReaderErrorCategory)

    The first two are one cycle, and it is the one that matters: an application layer that depends on the
    presentation layer cannot be tested or reused without the UI, and "which direction does this
    dependency point" stops having an answer. The second is the integration layer and the reading domain
    depending on each other, which is exactly what "Notion API DTOs must not leak into ... ReaderDocument
    construction" is about.

    This script does one mechanical thing: move files to the package their layer owns, and rewrite every
    import and fully-qualified reference so that nothing else changes. It is deliberately not a
    behaviour change.

    It is idempotent and dry-run by default, like tools/apply-spec-amendments.ps1: re-running it after a
    successful application reports that there is nothing left to do rather than corrupting the tree.

    Kotlin does not require a file's directory to match its package, which is why the two halves are
    separable: the package rewrite is what the compiler sees, and the file move is what a reader sees.
    Both are done here so that they cannot drift apart.

.PARAMETER Apply
    Perform the changes. Without it, only prints what would change.

.EXAMPLE
    pwsh -NoProfile -File tools/apply-layer-refactor.ps1
    pwsh -NoProfile -File tools/apply-layer-refactor.ps1 -Apply
#>
[CmdletBinding()]
param(
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$packageRoot = 'com\example\notionreader'
$mainRoot = Join-Path $repoRoot "app\src\main\java\$packageRoot"
$testRoot = Join-Path $repoRoot "app\src\test\java\$packageRoot"
$androidTestRoot = Join-Path $repoRoot "app\src\androidTest\java\$packageRoot"

if (-not (Test-Path $mainRoot)) { throw "Main source root not found: $mainRoot" }

$base = 'com.example.notionreader'

<#
    The layer map: which package each type belongs to, and therefore which package it is imported from.

    One row per (source package -> destination package). A row's `Names` are the types that move with it.
    Top-level functions and extension properties are listed here too, because they are imported by
    fully-qualified name exactly like a class is, and forgetting one produces an unresolved reference that
    looks like a missing dependency rather than a missed rename.

    `reader` appears seven times on purpose: it is the package that had six layers inside it, and the
    whole point of this refactor is that the domain, the queue/session domain, the persistence codec, the
    mapper, the Player and the session stop sharing one namespace.
#>
$plan = @(
    # --- reader -> domain : the pure model. No Android, no HTTP, no I/O. ---------------------------------
    @{ Old = 'reader'; New = 'domain'; Names = @(
            'ReaderDocument', 'ReaderSection', 'ReaderChunk', 'ReaderChunkKind', 'ChunkMetadata',
            'ReaderDocumentPosition', 'PositionMatch', 'ReaderPositionResolution',
            'ReaderIndexEntry',
            'ReaderErrorCategory', 'ReaderLoadException', 'ReaderLoadCancelledException'
        ) }

    # --- reader -> domain.queue : the Active Queue domain (Product contract: "Active Queue/session domain").
    @{ Old = 'reader'; New = 'domain.queue'; Names = @(
            'ActiveQueue', 'QueuedNote', 'QueueOrigin', 'QueueState'
        ) }

    # --- reader -> domain.session : the session record, its port, and the resume/history rules. ---------
    @{ Old = 'reader'; New = 'domain.session'; Names = @(
            'StoredSession', 'SessionStore', 'ListeningHistory', 'ResumeDecision', 'ResumePolicy'
        ) }

    # --- browse -> domain.discovery : normalized discovery metadata and the query over it. --------------
    @{ Old = 'browse'; New = 'domain.discovery'; Names = @(
            'BrowseQuery', 'BrowseSort', 'BrowseResults', 'applyTo'
        ) }

    # --- reader -> player : the Player layer, plus the speech port it depends on. ----------------------
    @{ Old = 'reader'; New = 'player'; Names = @(
            'ReaderEngine', 'ReaderEngineImpl', 'ReaderState', 'ReaderSectionSpan'
        ) }
    @{ Old = 'speech'; New = 'player'; Names = @('SpeechEngine') }

    # --- reader/app -> application : use cases, application state, read models, navigation. ------------
    @{ Old = 'reader'; New = 'application'; Names = @(
            'ReaderSession', 'LoadReaderDocument', 'DefaultLoadReaderDocument', 'DocumentLoadState',
            'readerErrorCategory', 'readerErrorMessage', 'asReaderLoadException', 'isReaderErrorRetryable'
        ) }
    @{ Old = 'app'; New = 'application'; Names = @(
            'ReaderApp', 'NoteViewer', 'QueueLoader', 'BrowseIndexStatus', 'BrowseModel', 'BrowseState',
            'ReaderDestination', 'ReaderNavigation', 'ReaderNavigator', 'ContinueListeningCard'
        ) }

    # --- notion -> data.content : normalized content, and the mapping from it into the reader domain. ---
    @{ Old = 'notion'; New = 'data.content'; Names = @(
            'NormalizedBlock', 'NormalizedKind', 'NotionBlockNormalizer', 'BlockChildrenSource',
            'DEFAULT_MAX_BLOCK_DEPTH', 'ReaderDocumentMapper', 'TextChunker', 'DEFAULT_MAX_CHUNK_CHARS'
        ) }

    # --- notion -> data.notion : the API client and its DTOs. ------------------------------------------
    @{ Old = 'notion'; New = 'data.notion'; Names = @(
            'NotionApi', 'KtorNotionApi', 'NotionRepository', 'NotionPage', 'NotionBlock', 'NotionBlockList',
            'NotionCodeBlock', 'NotionErrorBody', 'NotionPageList', 'NotionProperty', 'NotionRichText',
            'NotionTextBlock', 'NotionTodoBlock', 'NotionAnnotations',
            'normalizeNotionPageId', 'notionJson', 'failureFor'
        ) }

    # --- reader -> data.local : the wire format. A codec is an adapter, not a domain type. -------------
    @{ Old = 'reader'; New = 'data.local'; Names = @('SessionCodec') }
    @{ Old = 'app'; New = 'data.local'; Names = @('DataStoreSessionStore') }

    # --- speech -> platform : the Android TTS adapter, behind the port in `player`. --------------------
    @{ Old = 'speech'; New = 'platform'; Names = @('AndroidSpeechEngine') }

    # --- app -> ui : a formatter is presentation, not application state. --------------------------------
    @{ Old = 'app'; New = 'ui'; Names = @('describeLastActivity') }
)

# ---------------------------------------------------------------------------------------------------------
# File moves. The package line and the file's location are kept in step so a reader is not misled.
# ---------------------------------------------------------------------------------------------------------
$fileMoves = @{
    'app\BrowseModel.kt'               = 'application'
    'app\DataStoreSessionStore.kt'     = 'data\local'
    'app\NoteViewer.kt'                = 'application'
    'app\QueueLoader.kt'               = 'application'
    'app\ReaderApp.kt'                 = 'application'
    'app\ReaderDestination.kt'         = 'application'
    'app\ReaderNavigator.kt'           = 'application'
    'app\RelativeTime.kt'              = 'ui'
    'browse\BrowseQuery.kt'            = 'domain\discovery'
    'notion\NormalizedBlock.kt'        = 'data\content'
    'notion\NotionApi.kt'              = 'data\notion'
    'notion\NotionBlockNormalizer.kt'  = 'data\content'
    'notion\NotionModels.kt'           = 'data\notion'
    'notion\NotionRepository.kt'       = 'data\notion'
    'reader\ActiveQueue.kt'            = 'domain\queue'
    'reader\ListeningHistory.kt'       = 'domain\session'
    'reader\LoadReaderDocument.kt'     = 'application'
    'reader\ReaderDocument.kt'         = 'domain'
    'reader\ReaderDocumentMapper.kt'   = 'data\content'
    'reader\ReaderDocumentPosition.kt' = 'domain'
    'reader\ReaderEngine.kt'           = 'player'
    'reader\ReaderIndexEntry.kt'       = 'domain'
    'reader\ReaderLoadError.kt'        = 'domain'
    'reader\ReaderSession.kt'          = 'application'
    'reader\ReaderState.kt'            = 'player'
    'reader\ResumePolicy.kt'           = 'domain\session'
    'reader\SessionCodec.kt'           = 'data\local'
    'reader\SessionStore.kt'           = 'domain\session'
    'reader\StoredSession.kt'          = 'domain\session'
    'reader\TextChunker.kt'            = 'data\content'
    'speech\AndroidSpeechEngine.kt'    = 'platform'
    'speech\SpeechEngine.kt'           = 'player'
}

<#
    The same move for the test roots.

    Tests have to move with the code, and not only for tidiness. A test that referenced its subject through
    same-package resolution - `ResumePolicyTest` sitting in `com.example.notionreader.reader` and naming
    `ResumePolicy` with no import - has no import for this script to rewrite, so leaving it in place would
    produce an unresolved reference rather than a renamed one. Moving it to the package its subject now
    lives in preserves exactly the resolution it already relied on.

    A shared fixture is placed with the type it is a double *of* (`FakeReaderEngine` with `ReaderEngine`,
    `TestDocument` with `ReaderDocument`), not with its first caller, because the second caller is what
    would otherwise be silently coupled to the first one's package.
#>
$testFileMoves = @{
    'app\BrowseModelTest.kt'               = 'application'
    'app\ReaderAppHistoryTest.kt'          = 'application'
    'app\ReaderAppTest.kt'                 = 'application'
    'app\ReaderNavigatorTest.kt'           = 'application'
    'app\RelativeTimeTest.kt'              = 'ui'
    'browse\BrowseQueryTest.kt'            = 'domain\discovery'
    'notion\FixtureNotionApi.kt'           = 'data\notion'
    'notion\KtorNotionApiTest.kt'          = 'data\notion'
    'notion\NotionRepositoryTest.kt'       = 'data\notion'
    'notion\ReaderFixtureContractTest.kt'  = 'data\content'
    'reader\FakeReaderEngine.kt'           = 'player'
    'reader\InMemorySessionStore.kt'       = 'domain\session'
    'reader\LoadReaderDocumentTest.kt'     = 'application'
    'reader\ReaderEngineTest.kt'           = 'player'
    'reader\ReaderIdentityStabilityTest.kt' = 'data\content'
    'reader\ReaderSessionQueueTest.kt'     = 'application'
    'reader\ReaderStateTest.kt'            = 'player'
    'reader\ResumePolicyTest.kt'           = 'domain\session'
    'reader\SessionPersistenceTest.kt'     = 'domain\session'
    'reader\TestDocument.kt'               = 'domain'
    'speech\FakeSpeechEngine.kt'           = 'player'
}

# ---------------------------------------------------------------------------------------------------------
# 1. Package declarations.
# ---------------------------------------------------------------------------------------------------------
$packageEdits = @()
foreach ($root in @(
        @{ Path = $mainRoot; Moves = $fileMoves },
        @{ Path = $testRoot; Moves = $testFileMoves },
        @{ Path = $androidTestRoot; Moves = @{} }
    )) {
    foreach ($relativePath in $root.Moves.Keys) {
        $fullPath = Join-Path $root.Path $relativePath
        if (-not (Test-Path $fullPath)) { continue }

        $layer = $root.Moves[$relativePath] -replace '\\', '.'
        $wanted = "$base.$layer"

        $current = Select-String -Path $fullPath -Pattern '^package\s+(.+)$' | Select-Object -First 1
        if ($null -eq $current) { throw "No package declaration in $relativePath" }

        if ($current.Matches[0].Groups[1].Value.Trim() -ne $wanted) {
            $packageEdits += [pscustomobject]@{
                File = $fullPath
                From = $current.Matches[0].Groups[1].Value.Trim()
                To   = $wanted
            }
        }
    }
}

# ---------------------------------------------------------------------------------------------------------
# 2. References.
#
# Longest-old-first, and guarded by a negative lookahead for a word character. The guard is the important
# part: `com.example.notionreader.reader.ReaderDocument` is a prefix of
# `com.example.notionreader.reader.ReaderDocumentPosition`, so an unguarded replace of the shorter name
# would silently rewrite the longer type into the wrong package.
# ---------------------------------------------------------------------------------------------------------
$replacements = New-Object System.Collections.Generic.List[object]
foreach ($row in $plan) {
    foreach ($name in $row.Names) {
        $from = "$base.$($row.Old).$name"
        $to = "$base.$($row.New).$name"
        if ($from -ne $to) {
            $replacements.Add([pscustomobject]@{ From = $from; To = $to })
        }
    }
}
$ordered = $replacements | Sort-Object -Property { $_.From.Length } -Descending

$scannableExtensions = @('.kt', '.kts')
$sourceFiles = @()
$sourceFiles += Get-ChildItem (Join-Path $repoRoot 'app\src') -Recurse -File |
    Where-Object { $scannableExtensions -contains $_.Extension }

$referenceEdits = @()
foreach ($file in $sourceFiles) {
    $text = Get-Content $file.FullName -Raw
    $updated = $text
    $hits = 0

    foreach ($replacement in $ordered) {
        $pattern = [regex]::Escape($replacement.From) + '(?![\w])'
        $matches = [regex]::Matches($updated, $pattern)
        if ($matches.Count -gt 0) {
            $hits += $matches.Count
            $updated = [regex]::Replace($updated, $pattern, $replacement.To)
        }
    }

    if ($hits -gt 0) {
        $referenceEdits += [pscustomobject]@{
            File  = $file.FullName
            Hits  = $hits
            Text  = $updated
        }
    }
}

# ---------------------------------------------------------------------------------------------------------
# Report, or apply.
# ---------------------------------------------------------------------------------------------------------
$moveCount = 0
foreach ($root in @(
        @{ Path = $mainRoot; Moves = $fileMoves },
        @{ Path = $testRoot; Moves = $testFileMoves }
    )) {
    $moveCount += ($root.Moves.Keys | Where-Object { Test-Path (Join-Path $root.Path $_) }).Count
}
$referenceHitCount = ($referenceEdits | Measure-Object -Property Hits -Sum).Sum
if ($null -eq $referenceHitCount) { $referenceHitCount = 0 }

Write-Output "Layer refactor"
Write-Output "  files to move          : $moveCount"
Write-Output "  package lines to change: $($packageEdits.Count)"
Write-Output "  files with references  : $($referenceEdits.Count) ($referenceHitCount occurrences)"
Write-Output ""

if (-not $Apply) {
    Write-Output "Dry run. Re-run with -Apply to write."
    foreach ($edit in $packageEdits) {
        $relative = $edit.File.Replace("$mainRoot\", '')
        Write-Output ("  package  {0,-40} {1} -> {2}" -f $relative, $edit.From, $edit.To)
    }
    foreach ($edit in $referenceEdits) {
        Write-Output ("  refs     {0,-40} {1} occurrence(s)" -f ($edit.File.Replace("$repoRoot\", '')), $edit.Hits)
    }
    exit 0
}

# Both edits are composed onto one text per file, in this order, before anything is written.
#
# Doing them separately is the bug this replaced: the reference rewrite was computed from the file's
# original text, so assigning it after the package rewrite discarded the package rewrite for every file
# that needed both - which is most of them - and the run would have looked successful while leaving every
# moved file declaring its old package.
$packageByFile = @{}
foreach ($edit in $packageEdits) { $packageByFile[$edit.File] = $edit.To }

$touched = @($packageByFile.Keys) + @($referenceEdits | ForEach-Object { $_.File })
foreach ($path in ($touched | Sort-Object -Unique)) {
    $text = Get-Content $path -Raw

    if ($packageByFile.ContainsKey($path)) {
        $wanted = $packageByFile[$path]
        $text = [regex]::Replace($text, '^package\s+.+$', "package $wanted", 'Multiline')
    }

    foreach ($replacement in $ordered) {
        $pattern = [regex]::Escape($replacement.From) + '(?![\w])'
        $text = [regex]::Replace($text, $pattern, $replacement.To)
    }

    # ReadAllText/WriteAllText rather than Get-Content | Set-Content, because the latter normalises line
    # endings and would turn every touched file into a whole-file diff.
    [System.IO.File]::WriteAllText($path, $text)
}

# Move the files last, so a move cannot be half-applied over a tree whose packages were not rewritten.
$oldPackages = @('app', 'browse', 'notion', 'reader', 'speech')
foreach ($root in @(
        @{ Path = $mainRoot; Moves = $fileMoves },
        @{ Path = $testRoot; Moves = $testFileMoves }
    )) {
    foreach ($relativePath in $root.Moves.Keys) {
        $from = Join-Path $root.Path $relativePath
        if (-not (Test-Path $from)) { continue }

        $destinationDirectory = Join-Path $root.Path $root.Moves[$relativePath]
        if (-not (Test-Path $destinationDirectory)) {
            New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        }

        $to = Join-Path $destinationDirectory (Split-Path -Leaf $relativePath)
        if (Test-Path $to) { throw "Refusing to overwrite an existing file: $to" }

        Move-Item -Path $from -Destination $to
        Write-Output ("  moved    {0,-46} -> {1}" -f $relativePath, $root.Moves[$relativePath])
    }

    # Remove directories the move emptied, so the old layer names do not linger as empty folders.
    foreach ($directory in $oldPackages) {
        $path = Join-Path $root.Path $directory
        if ((Test-Path $path) -and -not (Get-ChildItem $path -Recurse -File | Select-Object -First 1)) {
            Remove-Item $path -Recurse -Force
            Write-Output ("  removed empty {0}" -f ($path.Replace("$repoRoot\", '')))
        }
    }
}

Write-Output ""

# The import repair below is superseded, and deliberately unreachable.
#
# It built its own index of importable names and then added an import for every name that merely
# *appeared* in a file, and two things were wrong with that. `fun interface BlockChildrenSource` was read
# as declaring a name called `interface`, so every file containing the word inherited an import for it.
# And PowerShell's `-match` is case-insensitive, so `readerErrorCategory` was considered present in
# `ReaderErrorCategory` and the domain gained an import from the application layer - the exact edge this
# whole migration exists to remove, invisible because Kotlin does not report an unused import.
#
# The lesson generalises: a scan can decide what is *legal* to import, but not what is *needed*. Only the
# compiler can answer the second question, so tools/fix-unresolved-imports.ps1 asks it, and converges.
# This block is kept as the record of a wrong approach beside the right one, but it must not run.
& (Join-Path $PSScriptRoot 'fix-unresolved-imports.ps1')
exit 0

Write-Output "Repairing imports that same-package resolution used to provide."

<#
    A type that leaves its package breaks every sibling that named it unqualified.

    Those references have no import line to rewrite, so the earlier passes cannot see them:
    `AndroidSpeechEngine` implemented `SpeechEngine` in the same package, `ReaderSession` named
    `ActiveQueue` in the same package, `NotionBlockNormalizer` named `NotionBlock` in the same package.
    Each is now in a different layer and needs the import the language never required before.

    The index of what is importable is built by scanning the tree rather than written out here, and that
    is a correction rather than a preference. The first version used a hand-written list of names, and a
    hand-written list of names is exactly the wrong instrument: it missed `positionAt`, a top-level
    extension function, because the name of an extension function is not the first identifier on its line
    - `fun ReaderDocument.positionAt(...)` declares `positionAt`, not `ReaderDocument` - and a name that
    is absent from the list fails silently, producing an unresolved reference in a file the tool did not
    visit. Deriving the index means a name cannot be forgotten, only mis-parsed, and a mis-parse is
    visible in the count.

    The scan is deliberately generous: a missed import is a compile error, which is loud, and a spurious
    one is an unused-import warning, which is quiet. Only two cases are excluded, and both are known to be
    wrong rather than merely noisy - a name the file declares itself, and a name the file already reaches
    through a qualifier.
#>

# `fun ReaderDocument.positionAt(...)` declares `positionAt`; `val Throwable.readerErrorCategory`
# declares `readerErrorCategory`. The receiver may itself be dotted or nullable, so the receiver is
# consumed as a repeated `Name.` / `Name?.` prefix and whatever follows is the declaration.
$declarationPattern = '(?m)^(?:public |internal |private |protected |abstract |open |sealed |data |enum |value |annotation |const |lateinit |override |external |inline |suspend |operator |infix |tailrec )*' +
    '(?:class|interface|object|typealias)\s+([A-Za-z][A-Za-z0-9_]*)' +
    '|' +
    '(?m)^(?:public |internal |private |protected |abstract |open |sealed |data |enum |value |annotation |const |lateinit |override |external |inline |suspend |operator |infix |tailrec )*' +
    '(?:fun|val|var)\s+(?:<[^>\r\n]+>[ \t]+)?(?:[A-Za-z][A-Za-z0-9_]*\??\.)*([A-Za-z][A-Za-z0-9_]*)'

$sourceFiles = @()
foreach ($root in @($mainRoot, $testRoot, $androidTestRoot)) {
    if (Test-Path $root) {
        $sourceFiles += Get-ChildItem $root -Recurse -File -Filter *.kt
    }
}

# Annotations sit on their own lines throughout this codebase, and stripping them first keeps the
# declaration pattern to one shape instead of two.
function Get-ScannableCode([string]$text) {
    $code = [regex]::Replace($text, '(?m)^@.+?$', '')
    $code = [regex]::Replace($code, '(?s)/\*.*?\*/', '')
    return [regex]::Replace($code, '(?m)//.*$', '')
}

function Get-DeclaredNames([string]$code) {
    $names = @{}
    foreach ($match in [regex]::Matches($code, $declarationPattern)) {
        foreach ($group in @(1, 2)) {
            $value = $match.Groups[$group].Value
            if ($value) { $names[$value] = $true }
        }
    }
    return $names
}

# name -> FQN, for every name declared anywhere under com.example.notionreader. A name declared in more
# than one package is ambiguous and is dropped rather than guessed at, because importing the wrong one
# would compile and then do something else.
$importable = @{}
$ambiguous = @{}
foreach ($file in $sourceFiles) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $filePackage = ([regex]::Match($text, '(?m)^package\s+(.+)$')).Groups[1].Value.Trim()
    if (-not $filePackage.StartsWith($base)) { continue }

    $code = Get-ScannableCode $text
    foreach ($name in (Get-DeclaredNames $code).Keys) {
        $fqn = "$filePackage.$name"
        if ($importable.ContainsKey($name)) {
            if ($importable[$name] -ne $fqn) { $ambiguous[$name] = $true }
        } else {
            $importable[$name] = $fqn
        }
    }
}
foreach ($name in $ambiguous.Keys) { $importable.Remove($name) | Out-Null }

Write-Output ("  importable names indexed: {0} ({1} ambiguous, dropped)" -f $importable.Count, $ambiguous.Count)

$repairedFiles = 0
foreach ($file in $sourceFiles) {
    $text = [System.IO.File]::ReadAllText($file.FullName)

    $filePackage = ([regex]::Match($text, '(?m)^package\s+(.+)$')).Groups[1].Value.Trim()
    if (-not $filePackage.StartsWith($base)) { continue }

    $code = Get-ScannableCode $text
    $declared = Get-DeclaredNames $code

    $existingImports = @([regex]::Matches($text, '(?m)^import\s+(.+)$') | ForEach-Object { $_.Groups[1].Value.Trim() })

    # Prune before adding.
    #
    # The earlier pass compared names with PowerShell's `-match`, which is case-INsensitive, so the
    # extension property `readerErrorCategory` was considered present in `ReaderErrorCategory` and an
    # import was added for it. That import is not merely untidy: it made `domain` depend on
    # `application`, which is the one edge this whole refactor exists to remove, and Kotlin does not
    # report an unused import, so it would have survived every build.
    #
    # A name is required to be present in the code the import serves. A link inside KDoc is not code, so
    # an import existing only for a `[Type]` link is pruned and the link degrades to plain text.
    $keptImports = @()
    $pruned = 0
    foreach ($import in $existingImports) {
        if (-not $import.StartsWith("$base.")) { $keptImports += $import; continue }

        $simpleName = $import.Substring($import.LastIndexOf('.') + 1)
        $pattern = "(?<![\w.])$([regex]::Escape($simpleName))(?![\w])"
        if ([regex]::IsMatch($code, $pattern)) { $keptImports += $import } else { $pruned += 1 }
    }

    $alreadyImported = @{}
    foreach ($import in $keptImports) { $alreadyImported[$import] = $true }

    $needed = @()
    foreach ($name in $importable.Keys) {
        $fqn = $importable[$name]
        $owningPackage = $fqn.Substring(0, $fqn.Length - $name.Length - 1)

        # Same package: the reference already resolves. Declared here: importing would shadow it.
        if ($owningPackage -eq $filePackage) { continue }
        if ($declared.ContainsKey($name)) { continue }
        if ($alreadyImported.ContainsKey($fqn)) { continue }

        # Not preceded by a word character or a dot, so a name already reached through a qualifier does
        # not gain a redundant import.
        $pattern = "(?<![\w.])$([regex]::Escape($name))(?![\w])"
        if ([regex]::IsMatch($code, $pattern)) { $needed += $fqn }
    }

    $finalImports = @($keptImports + $needed | Sort-Object -Unique)
    $unchanged = ($finalImports.Count -eq $existingImports.Count) -and
        (@($finalImports | Where-Object { $existingImports -notcontains $_ }).Count -eq 0)
    if ($unchanged) { continue }

    $block = ($finalImports | ForEach-Object { "import $_" }) -join "`n"

    # A file that had no imports at all has no block to replace, and the first version of this pass
    # silently did nothing for exactly those files - which are the ones that most need it, since having
    # no imports is what same-package resolution looked like.
    $updated = if ([regex]::IsMatch($text, '(?m)^import ')) {
        [regex]::Replace($text, '(?m)^(?:import .+\r?\n)+', { param($m) $block + "`n" })
    } else {
        [regex]::Replace($text, '(?m)^(package .+)$', { param($m) $m.Groups[1].Value + "`n`n" + $block }, 1)
    }

    if ($updated -ne $text) {
        [System.IO.File]::WriteAllText($file.FullName, $updated)
        $repairedFiles += 1
        Write-Output ("  imports  {0,-58} +{1} -{2}" -f ($file.FullName.Replace("$repoRoot\", '')), $needed.Count, $pruned)
    }
}

Write-Output ""
Write-Output "Applied. $repairedFiles file(s) had imports repaired."
