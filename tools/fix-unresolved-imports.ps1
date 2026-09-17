param(
    [int]$MaxRounds = 6
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$packageRoot = 'com\example\notionreader'
$base = 'com.example.notionreader'
$sourceRoots = @(
    (Join-Path $repoRoot "app\src\main\java\$packageRoot"),
    (Join-Path $repoRoot "app\src\test\java\$packageRoot"),
    (Join-Path $repoRoot "app\src\androidTest\java\$packageRoot")
)

# ---------------------------------------------------------------------------------------------------------
# A sound index of what can legally be imported.
#
# "Legal to import" is a narrower set than "declared", and the previous attempt at this repair conflated
# them. Three ways a name gets into an index it should not be in, all of which happened:
#
#   - `fun interface BlockChildrenSource` captured `interface` as a declaration name, so every file
#     containing the word `interface` gained an import for it.
#   - a `private fun NotionTextBlock?.linkTargets()` is real but unimportable from another file.
#   - `fun NotionPage.title()` is importable, but a file that declares its own `val title` matched the
#     reference test and gained an import it never uses.
#
# The third case is why adding imports is driven by the compiler rather than by a scan. A scan can only
# ask "does this name appear"; only the compiler can answer "does this reference resolve". So the index
# decides what is *legal*, the compiler decides what is *needed*, and nothing else is guessed.
# ---------------------------------------------------------------------------------------------------------
$modifiers = '(?:public |internal |private |protected |abstract |open |sealed |data |enum |value |annotation |const |lateinit |override |external |inline |suspend |operator |infix |tailrec |fun )*'
$typeDeclaration = "(?m)^($modifiers)(?:class|interface|object|typealias)\s+([A-Za-z][A-Za-z0-9_]*)"
$callableDeclaration = "(?m)^($modifiers)(?:fun|val|var)\s+(?:<[^>\r\n]+>[ \t]+)?(?:[A-Za-z][A-Za-z0-9_]*\??\.)*([A-Za-z][A-Za-z0-9_]*)"

# A captured name that is itself a keyword means the pattern matched a keyword position, not a
# declaration. Rejecting them is cheaper than making the pattern airtight.
$keywords = @(
    'interface', 'class', 'object', 'fun', 'val', 'var', 'typealias', 'enum', 'data', 'sealed', 'open',
    'abstract', 'internal', 'public', 'private', 'protected', 'const', 'inline', 'suspend', 'operator',
    'infix', 'tailrec', 'external', 'lateinit', 'override', 'annotation', 'value', 'companion', 'init',
    'constructor', 'get', 'set', 'by', 'where', 'this', 'super', 'out', 'in'
)

# Ordinal dictionaries, not `@{}`.
#
# PowerShell's `@{}` literal builds a case-INSENSITIVE Hashtable, which quietly merged the pairs this
# codebase is full of: `ReaderErrorCategory` (the domain enum) and `readerErrorCategory` (the extension
# property that maps a failure onto it), `QueuedNote` and `queuedNote` (the test builder of one),
# `StoredSession` and `storedSession`. Each pair looked like one name declared in two packages, so every
# one of them was dropped as ambiguous - and a name missing from the index is invisible: the prune does
# not remove it, the compiler is not asked about it, and nothing reports it. The whole index silently
# lost its most-referenced entries.
# The comma is load-bearing.
#
# A PowerShell function enumerates what it returns, so `return <an empty HashSet>` emits nothing and the
# caller receives $null - an empty collection and a missing one become the same value. `,@collection`
# wraps it in a one-element array, which the pipeline then enumerates once, yielding the collection
# itself. Found here the same way the export tool's missing list numbers were found: the symptom pointed
# at the consumer (a method call on null) and the cause was in the producer.
function New-OrdinalMap {
    return ,[System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
}

function New-OrdinalSet {
    return ,[System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
}

function Get-ImportableIndex {
    $index = New-OrdinalMap
    $ambiguous = New-OrdinalSet

    foreach ($root in $sourceRoots) {
        if (-not (Test-Path $root)) { continue }

        foreach ($file in (Get-ChildItem $root -Recurse -File -Filter *.kt)) {
            $text = [System.IO.File]::ReadAllText($file.FullName)
            $package = ([regex]::Match($text, '(?m)^package\s+(.+)$')).Groups[1].Value.Trim()
            if (-not $package.StartsWith($base)) { continue }

            # Annotations own their line in this codebase; removing them keeps the declaration patterns
            # to one shape apiece. Comments go too, so prose cannot invent a declaration.
            $code = [regex]::Replace($text, '(?m)^@.+?$', '')
            $code = [regex]::Replace($code, '(?s)/\*.*?\*/', '')
            $code = [regex]::Replace($code, '(?m)//.*$', '')

            foreach ($pattern in @($typeDeclaration, $callableDeclaration)) {
                foreach ($match in [regex]::Matches($code, $pattern)) {
                    if ($match.Groups[1].Value -match 'private ') { continue }

                    $name = $match.Groups[2].Value
                    if (-not $name) { continue }
                    if ($keywords -contains $name) { continue }

                    $fqn = "$package.$name"
                    if ($index.ContainsKey($name)) {
                        if ($index[$name] -ne $fqn) { $ambiguous.Add($name) | Out-Null }
                    } else {
                        $index[$name] = $fqn
                    }
                }
            }
        }
    }

    foreach ($name in $ambiguous) { $index.Remove($name) | Out-Null }

    return [pscustomobject]@{ Index = $index; Ambiguous = @($ambiguous) }
}

function Get-FileImports([string]$text) {
    return @([regex]::Matches($text, '(?m)^import\s+(.+)$') | ForEach-Object { $_.Groups[1].Value.Trim() })
}

function Set-FileImports([string]$path, [string]$text, [string[]]$imports) {
    # A SortedSet with an ORDINAL comparer, not `Sort-Object -Unique`.
    #
    # `Sort-Object -Unique` compares case-insensitively, so it treats `io.ktor.http.ContentType` and
    # `io.ktor.http.contentType` as the same import and keeps one. Ktor needs both: one is the type and
    # one is the request-builder function. The earlier pass silently dropped the type from a file that
    # then failed to compile, and because the surviving import was for a name one character different,
    # the loss read as a missing dependency rather than as a deduplication.
    #
    # Building the set in ordinal order also sorts the block and drops empty entries, which is what the
    # rest of the import handling relies on.
    $unique = [System.Collections.Generic.SortedSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($import in $imports) {
        if ([string]::IsNullOrWhiteSpace($import)) { continue }
        $unique.Add($import.Trim()) | Out-Null
    }

    $block = ($unique | ForEach-Object { "import $_" }) -join "`n"

    if ([regex]::IsMatch($text, '(?m)^import ')) {
        return [regex]::Replace($text, '(?m)^(?:import .+\r?\n)+', { param($m) $block + "`n" })
    }

    # A file with no import block at all has none to replace, and inserting after the package line is the
    # only place the block can go.
    return [regex]::Replace($text, '(?m)^(package .+)$', { param($m) $m.Groups[1].Value + "`n`n" + $block }, 1)
}

# ---------------------------------------------------------------------------------------------------------
# Prune first, then let the compiler ask for what is missing.
# ---------------------------------------------------------------------------------------------------------
$indexResult = Get-ImportableIndex
$importable = $indexResult.Index
Write-Output ("Importable names: {0} ({1} ambiguous, skipped)" -f $importable.Count, $indexResult.Ambiguous.Count)
if ($indexResult.Ambiguous.Count -gt 0) {
    Write-Output ("  ambiguous: {0}" -f ($indexResult.Ambiguous -join ', '))
}

$prunedTotal = 0
foreach ($root in $sourceRoots) {
    if (-not (Test-Path $root)) { continue }

    foreach ($file in (Get-ChildItem $root -Recurse -File -Filter *.kt)) {
        $text = [System.IO.File]::ReadAllText($file.FullName)
        $imports = Get-FileImports $text

        $kept = @()
        foreach ($import in $imports) {
            if (-not $import.StartsWith("$base.")) { $kept += $import; continue }

            # Remove anything the index does not vouch for: a name that is not declared, a package
            # segment, or a declaration that is private in its file. The comparison is exact and
            # ordinal, because a case-insensitive one would accept an import whose name only differs in
            # case from a real declaration - which is precisely the mistake that put `readerErrorCategory`
            # into a domain file that meant `ReaderErrorCategory`.
            $simpleName = $import.Substring($import.LastIndexOf('.') + 1)
            $vouched = $importable.ContainsKey($simpleName) -and $importable[$simpleName] -eq $import

            if ($vouched) { $kept += $import }
            else {
                $prunedTotal += 1
                Write-Output ("  prune    {0,-52} {1}" -f ($file.FullName.Replace("$repoRoot\", '')), $import)
            }
        }

        if ($kept.Count -ne $imports.Count) {
            [System.IO.File]::WriteAllText($file.FullName, (Set-FileImports $file.FullName $text $kept))
        }
    }
}
Write-Output "Pruned $prunedTotal import(s)."

# Not anchored to the start of a line: after a wrapped diagnostic is rejoined, the error it describes is
# no longer at one.
#
# The path is captured lazily rather than as `[^:]+`, because a Windows path contains a colon of its own
# in the drive letter. `[^:]+` matched `D`, then demanded a line number where `/repos/...` was, and the
# whole pattern failed - and because a pattern that never matches is indistinguishable from a build with
# no errors, the tool reported success twice while the tree was full of unresolved references.
$errorPattern = "e: file:///(?<path>.+?):(?<line>\d+):(?<column>\d+) Unresolved reference '(?<name>[^']+)'\."
$unresolvedPattern = "e: file:///(?<path>.+?):(?<line>\d+):(?<column>\d+) Unresolved reference\."

for ($round = 1; $round -le $MaxRounds; $round++) {
    Write-Output ""
    Write-Output "Round $round : compiling"

    $output = & (Join-Path $repoRoot 'gradlew.bat') :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --console=plain 2>&1
    $raw = ($output | ForEach-Object { "$_" }) -join "`n"

    if (-not ($raw -match '(?m)^e: ')) {
        Write-Output "No compile errors remain."
        break
    }

    # Gradle wraps a long diagnostic at the console width, so a single error can arrive as two lines and
    # the second one begins mid-path: `e: file:///D:/.../Ma` then `inActivity.kt:102:22 Assignment type
    # mismatch`. Matching the raw output therefore fails on exactly the errors that carry long paths -
    # which is all of them in this tree - and the failure is silent, because "no name matched" is
    # indistinguishable from "nothing was wrong". Rejoining a line that does not itself start a new
    # diagnostic removes the wrap without depending on the terminal's width.
    $compact = [regex]::Replace($raw, "\r?\n(?![ew]: )", '')

    # Adding an import for one unresolved name often resolves several reported errors at once, and it can
    # expose errors the compiler had not reached, so this is a fixpoint rather than a single pass.
    $wanted = @{}
    $unnamed = 0
    foreach ($match in [regex]::Matches($compact, $errorPattern)) {
        $name = $match.Groups['name'].Value
        if (-not $importable.ContainsKey($name)) { continue }

        $path = $match.Groups['path'].Value
        if (-not $wanted.ContainsKey($path)) { $wanted[$path] = New-OrdinalSet }
        $wanted[$path].Add($importable[$name]) | Out-Null
    }
    foreach ($match in [regex]::Matches($compact, $unresolvedPattern)) { $unnamed += 1 }

    if ($wanted.Count -eq 0) {
        Write-Output "No unresolved reference names this tool can map. $unnamed unnamed error(s) remain."
        break
    }

    foreach ($path in $wanted.Keys) {
        $file = $path -replace '/', '\'
        if (-not (Test-Path $file)) {
            Write-Output "  SKIP     $path (no such file)"
            continue
        }

        $text = [System.IO.File]::ReadAllText($file)
        $added = @($wanted[$path])
        $imports = @(Get-FileImports $text) + $added

        $result = Set-FileImports $file $text $imports
        [System.IO.File]::WriteAllText($file, $result)

        $verified = [System.IO.File]::ReadAllText($file)
        $landed = ($verified.Length -ne $text.Length)
        Write-Output ("  add      {0,-52} +{1} {2} landed={3}" -f ($file.Replace("$repoRoot\", '')), $added.Count, ($added -join ','), $landed)
    }
}

Write-Output ""
Write-Output "Done."
