# Adaptive Discovery storage and configurable Note cache

Status: approved for implementation; live governing contracts amended and mirrors regenerated.

## Intent

Allow offline listening and Discovery to scale without asking users to size an
internal index. Discovery grows according to actual eligible indexed content,
not Note count. Users control the Note-cache limit and may optionally set an
advanced Discovery maximum. Storage capacity never authorizes extra downloads,
new source eligibility, or unlimited background processing.

## Boundary before this change

The file Note cache defaults to 50 MiB and 200 entries. Discovery stores share a
64 MiB budget. Settings expose usage but no size control. Discovery is cache-only;
this change retains that requirement. Indexing remote, uncached Notes would need
a separate source-lifecycle and privacy design. The active model uses 768-dimensional
embeddings; the supplied 384-dimensional examples are illustrative, not sizing data.

## Proposed policy

Use a 128 MiB Note-cache byte default for fresh installations and legacy settings
without an explicit limit; preserve explicit user choices. Add a persisted byte
limit slider with discrete, spoken size steps and equivalent increase/decrease
actions. Retain the existing 200-entry resource guard and report it separately from
the byte limit. Discovery defaults to Automatic, with no fixed 64 MiB ceiling.
The previous limit remains a benchmark reference. An advanced Custom maximum
provides an accessible size slider; selecting it does not preallocate space.

Before significant writes, require physically available space to cover the write,
atomic replacement/staging peak and a reserve of max(2 GiB, 10% of filesystem
capacity). Use the app's actual storage volume. Android allocatable-byte estimates
may supply an additional admission signal but never replace the physical reserve
or guarantee a successful write. Unknown storage measurements defer background
growth with an accurate explanation and retry after measurements recover.

Expose an explicit extended-storage option permitting up to 80% of the remaining
safe envelope (current managed bytes plus free bytes above the reserve). The
percentage is a ceiling, never a target. Account for Note cache, Discovery,
temporary files and model bytes together for device safety, while maintaining
separate category limits. Persist user choices in bytes so falling free space
does not silently rewrite preferences. Recheck admission against current space.
Ordinary automatic Discovery growth needs no repeated consent.

## Cleanup and failure behavior

## Default sizing for 100 Notes

The default capacity target is 100 Notes, not an automatic download instruction or
a guarantee for unbounded Note lengths. Allow 1 MiB encoded JSON per Note:
100 MiB of committed documents fits in 128 MiB with 28 MiB headroom. Existing
200-entry protection does not prevent this workload; preserve it initially rather
than replacing it speculatively. For larger configured byte limits, surface the
entry bound honestly until a measured resource-safe replacement is implemented.
Add COUNT_100 as a selectable automatic-download count; keep the current seven-day
fresh-install automatic-download policy and preserve existing preferences.

FileSemanticIndexStore writes a 12-byte prefix, UTF-8 JSON header (including both
excerpt and input), and passages * dimensions * 4 bytes. The active model has
768-dimensional float32 output; INT4 describes model weights, not stored vectors.
For 100 Notes the vector payload is 2.93/14.65/29.30/146.48 MiB at
10/50/100/500 passages per Note. Assuming 2 KiB JSON metadata per passage, one
index copy is 4.88/24.42/48.83/244.14 MiB, including 100 prefixes. Two complete
copies are 9.77/48.83/97.66/488.28 MiB. These are arithmetic planning estimates,
not measurements: UTF-8 escaping, context, IDs and excerpt lengths change headers.
The chunker splits each reader chunk independently, with a 256-token input ceiling
and 32-token overlap; Note byte size alone cannot predict passage count.

Automatic Discovery has no preallocated size or fixed 64/128/256 MiB ceiling.
Use 256 MiB as the default value when a user first chooses Custom maximum, leaving
Automatic selected by default. The 100-Note/10,000-passage validation workload must
include actual encoded headers, progress/staging peaks and topic/checkpoint bytes;
256 MiB is planning headroom, not proof all content fits. The model pack adds
206,696,682 bytes (~197.12 MiB) separately, including its own acquisition peak.
The historical 19,304,090-byte index benchmark predates the current embedding
width and must not be presented as a current 768-dimensional measurement.

Shipping-codec measurements on deterministic synthetic content: 100 near-one-MiB
documents occupy 104,493,870 bytes (~99.65 MiB). At 10/50/100/500 passages per
Note, 768-dimensional index bytes are 4,087,390 / 20,330,990 / 40,635,490 /
203,231,490; reusable progress occupies the same bytes in this deliberately
duplicated workload. Source text uses UTF-8 and escaped characters in the index
workload. These measurements validate the reference capacity, not every library's
size or topic quality. A separate valid synthetic 100-member topic and 99-connection
catalog occupy 218,775 bytes (142,248 catalog bytes included); a checkpoint copy
occupies another 218,775 bytes. This is a sizing fixture, not representative topic
formation or quality acceptance. Shipping atomic-write observation measures old
and staged files coexisting and proves an injected pre-commit I/O failure leaves
the old file and removes staging.
Retain per-record memory safeguards independently of library capacity: at most
8 MiB semantic headers and 64 MiB semantic/topic envelopes. Oversized records
report incomplete coverage with a resource-limit explanation and preserve prior
valid data. Validate file/header lengths before allocation.

### Cleanup details

Reclaim obsolete derived records and abandoned staging only when no active writer
or committed generation references them. Include overlapping old/new generations
and every derived store in accounting. Serialize admissions across stores so
concurrent writers cannot each spend the same available capacity. Preserve the
last valid generation on interruption, cancellation, corruption or write failure.

For Note-cache pressure, evict speculative prefetch content before least recently
used unprotected Notes. Protect the current listening Note and upcoming active
queue Notes through every write/prune path. If protected bytes exceed a requested
limit, explain that cleanup is deferred; do not silently break playback. Reducing
a limit presents the estimated removal impact before applying destructive cleanup.
Increasing a limit never recaches or downloads the whole library automatically.

Evicted sources invalidate dependent Discovery content under existing privacy
rules, including inspected views. Capacity pressure alone must not silently drop
valid Discovery records to claim complete coverage. Pause additional indexing,
report incomplete coverage and retain prior valid eligible results. Resume when
capacity recovers only if explicit Pause/index-clear/model-delete state permits it.
Saved queues, interests, history and listening snapshots are never cleanup targets.

## Presentation and execution

Offline & storage shows Note-cache usage/limit and cleanup impact. Discovery shows
Automatic or Custom maximum, measured derived usage and incomplete coverage when
blocked. Explain automatic local growth once when enabling Discovery; existing
users receive the explanation during migration. Show model usage separately.
Controls expose meaningful TalkBack labels and values without repeated live
announcements during slider adjustment. Commit preferences after adjustment.

Retain process-owned orchestration, foreground query priority, playback/resource
pauses, battery policy and bounded WorkManager windows. Add storage-not-low
scheduling as a coarse signal alongside write-time checks, never as their substitute.
Keep persistent indexes in private persistent storage, outside disposable cacheDir.

## Specification and implementation sequence

After this design is approved, amend the live Product, UI and Integration sources
in authority order, inspect their complete affected text, regenerate mirrors and
review all dependent specifications. Consolidate conflicting fixed-budget clauses;
do not leave an ambiguous additive amendment. Update the implementation companion
and README. Produce an implementation plan against the amended contracts before
product changes. Existing unrelated staged and unstaged work must be preserved.

## Verification

Failing-first JVM properties cover budget math/overflow, low or unknown space,
concurrent admissions, temporary peaks, custom limits, migration, shrink behavior,
protected sources, eviction order, stale-source invalidation, cancellation and
failed writes preserving prior data. Exercise shipping stores rather than copied
budget formulas. Verify sliders through Compose semantics, including TalkBack
adjustment actions and persistence. Run the repository verification skill, JVM
suite, assemble and applicable androidTest compilation/targeted device probes.
Human speech acceptance remains distinct from automated semantics evidence.
