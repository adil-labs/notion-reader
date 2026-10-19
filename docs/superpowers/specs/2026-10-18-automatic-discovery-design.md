# Automatic Discovery after setup — proposed design

Status: design and direct implementation plan approved by the user on 18 October
2026. Implementation and verification are tracked in the matching automatic-discovery
plan; approval alone does not establish completion.

## Confirmed current interaction defect

The user reports having to press Continue. Inspection confirms a literal
`Continue discovery` button in `TopicScreens.kt`: `discoveryProgress` maps a
status containing `next eligible foreground` to the Resume action even when
`DiscoveryState.paused` is false. Its handler calls `controller.resume()`.
This conflates an automatically resumable work-slice boundary with an explicit
user pause. `DiscoveryController` already increments its wake revision when
`TopicCoordinator.needsMoreWork` is true, so the button alone does not establish
which scheduling or checkpoint condition stopped the reported run.

A controller-level regression now observes the actual revision signal, starts
the foreground owner once, and completes a deliberately one-comparison-per-slice
fixture with six Notes, published topics and connections. It does not call Resume
or Refresh or change the sources to provoke another round. Removing the shipping
automatic-wake increment makes it stall after exactly one unfinished round.
The earlier test timeout came from a delegated fake missing `writeDetailed`
completion, not a production stall; the corrected test inspects persisted output.
This demonstrates foreground continuation for that fixture, not screen-off or
process-death scheduling or the cause of every reported private-library stall.

The implementation must remove this manual-continuation requirement and prove
with a multi-slice regression that subsequent topic and connection work is
actually executed. Changing only the button wording would not satisfy the user.
Explicit Pause/Resume controls in Settings retain their intentional meaning.

## User outcome

After explicit Discovery setup and model acquisition, eligible cached Notes are
indexed and their topics and connections are discovered without opening Discover,
pressing Generate, following interests, or repeatedly pressing Resume. Processing
also resumes automatically with the screen off or the app closed when Android
permits execution and power conditions allow it. This follows the user's explicit
18 October clarification. Discovery remains local and cache-bound.

## Scheduling choice

Use persistent, constrained WorkManager requests to grant bounded execution windows
to the existing single Discovery pipeline. Foreground changes can still trigger
immediate work. Both paths share one execution gate and durable checkpoints;
WorkManager is a scheduling entry point, not a second discovery engine. Refactor
the foreground-only bound-service ownership deliberately rather than running two
independent loops over the same stores.

A bound service alone cannot provide reliable execution after process death.
A permanent foreground service would introduce an ongoing notification and is
unnecessary for deferrable work. Prefer ordinary, non-expedited requests and short
resumable windows. Android may defer them; no promise of uninterrupted execution
or a fixed completion deadline is made. Force-stop requires the user to reopen
the app, as imposed by Android. Reboot and ordinary process death preserve pending
work through the scheduler and durable source/model/reset checkpoints.

## Eligibility and effort

Preserve the current eligibility floor: external power, or a known battery level
of at least 30 percent. Recheck the precise app threshold within execution; an
OS battery-not-low constraint alone does not express that threshold. Pause for
playback and resource pressure. Never weaken source withdrawal or reset handling.

Offer a persisted Settings choice named Discovery effort:

- Balanced (default): automatic bounded processing on sufficient battery or power,
  yielding between windows so other app work remains responsive.
- Charging only: the same complete analysis, scheduled only while connected to
  external power.

These choices control resource availability, not thresholds, corpus coverage,
evidence standards, or the number of topics the system is allowed to discover.
Avoid a third “maximum” mode until measurements support a useful distinction.
Window duration and backoff are implementation policy to be measured on device,
not claims of a guaranteed duty cycle under Android scheduling.

## Lifecycle and publication

Successful setup enables automatic discovery. Model installation, newly cached or
edited Notes, compatible pending checkpoints and eligible power transitions arrange
work without another user action. Queue subsequent unfinished slices without
requiring a new source edit. Unique scheduling coalesces repeated triggers.

An explicit user Pause remains respected across restarts. Clear index and Delete
model retain their existing deliberate rebuilding-disabled semantics; a subsequent
explicit Resume enables automatic processing again. Reset/deletion cancels pending
work and invalidates stale commits using the existing generation checks. No
automatic model updates or automatic remote-library downloads are introduced.

New consistent generations publish automatically. An already inspected topic or
connection stays pinned with Updates available; Refresh changes the inspected
snapshot only and must never be necessary to start or finish computation.

## Status and estimates

Settings and Discover distinguish processing, waiting for power, waiting for an
Android execution window, paused by the user, and incomplete storage coverage.
Retain indexed/eligible Note coverage and processed/eligible section coverage.
Show measured remaining embedding time with its explicit scope. Show topic and
connection analysis separately until measured estimates exist; do not present the
embedding-only estimate as a total completion time or count OS waiting as CPU time.

## Contract and implementation sequence

On approval, amend the live Product, UI and Integration specifications to supersede
foreground-only bulk-work restrictions and the bound-service-only worker lifetime.
Regenerate and diff the mirrors before production changes. Review architecture
and LayerRulesTest deliberately for the scheduler integration. Preserve the one
worker, private storage, privacy, accessibility and playback invariants.

Then write the implementation plan covering scheduler/coordinator ownership,
persisted effort settings, status mapping, cancellation and migration. This proposal
does not silently expand setup to fetch uncached Notion content.

## Verification required

Failing-first tests must prove setup automatically starts work, unchanged pending
slices complete without user interaction, repeated triggers never overlap inference,
power and effort changes resume work, and explicit pause/reset prevent rebuilding
or stale publication. Test real checkpoint recovery after process death, background
and screen-off execution, source invalidation and playback priority. Verify actual
subjects and connections with the real Gemma pack, not only scheduler completion.
Run the repository JVM/build/androidTest checks and wrapper-based targeted device
tests while preserving existing app data. Keep unobserved TalkBack speech and
large-library quality/resource gates explicitly separate.

## Documentation consulted

Official Android documentation was searched and fetched through the Android CLI:

- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running

Constraints may interrupt work, ordinary background execution can be deferred,
expedited execution has quotas, and long-running foreground workers introduce
notification/service-type obligations. The design uses resumability rather than
assuming a permanently running process.
