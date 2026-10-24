# Coordinated storage settings cleanup

## Intent

Make Discovery and Offline & storage understandable without knowing what an embedding or semantic index is. The user selected a coordinated cleanup of both screens. This is a presentation change using the existing Compose settings components and storage policies, not a new storage policy.

## Approaches

- Discovery-only cleanup: smaller change, but leaves conflicting terminology and hidden shared settings across screens.
- Coordinated cleanup (selected): keep the existing destinations, align their storage sections and explain their relationship. This avoids adding a new navigation destination or moving download preferences.
- Central storage screen: gives one overview, but adds navigation and a larger product/specification change. Deferred.

## Shared structure

Each storage category leads with its purpose, actual bytes used, then its management control and consequences. Display actual usage independently from a maximum; a maximum is permission to use space, not space already occupied or reserved. Preserve existing size formatting consistently, including MiB/GiB; do not relabel binary byte values as decimal MB/GB.

Use existing settings typography, full-width rows, dividers and accessible headings. Keep detailed policy explanations behind an accessible expandable `How storage works` row. Status and limit consequences remain visible, including when they indicate blocked work. Expanded explanations do not announce repeated background updates or move focus.

## Offline & storage

Keep current offline, download and refresh controls. In the storage section:

1. `Offline Notes`: actual count and bytes used, including zero usage. Explain that these are downloaded copies used for offline reading and Discovery.
2. `Storage limit`: existing persisted Note-cache byte limit and accessible adjustment control. Explain that increasing it does not download additional Notes by itself.
3. Explain that reducing the limit can remove offline copies and dependent Discovery results. Preserve the existing confirmation before a reduction that requires removal, and the existing protected-queue cleanup deferral.
4. Keep `Clear cached Notes`, with its existing confirmation and accurate source-removal consequences.
5. Place the existing shared extended-storage preference in the detailed explanation, labelled `Allow larger storage limits`. Explicitly explain that it affects both Offline Notes and Discovery custom limits. Keep any enabled-state summary visible when details are collapsed.

Move the default sizing example and separate 200-Note guard into the detailed explanation. Show the count-limit warning visibly when reached. Do not suggest increasing bytes removes the count guard.

## Discovery

Use a clearly headed storage section, separate from processing preferences and setup controls:

1. `Discovery data`: actual derived bytes used. Explain: `Helps find related Notes, topics and connections using your downloaded Notes.`
2. `Storage management`: two radio choices, `Automatic (recommended)` and `Set a maximum`, replacing the ambiguous automatic-storage switch. These map to the existing boolean; selecting a custom maximum preserves the persisted choice, including its existing default.
3. Automatic explanation: `Uses space as needed while keeping space free for your device.`
4. Custom mode reveals `Storage limit` and its size adjustment. Always explain: `When the limit is reached, preparation of additional Discovery data waits. Existing eligible results remain available.` Lowering the limit must not imply deletion of existing results.
5. `Downloaded model`: separate actual model-file bytes from Discovery data. Keep the approximately 207 MB pack download estimate in setup, distinct from actual installed bytes. Do not combine these into an unsupported total or call model bytes part of the custom data limit.
6. `How storage works`: explain separate model/data accounting, cache-only eligibility, the device reserve (the greater of 2 GiB or 10% of device capacity), and the shared larger-limit preference's location in Offline & storage. Preserve the bounded normal and extended choices.

Keep indexing, pause/resume, reset and model acquisition behavior unchanged. Group existing clear/rebuild/remove controls under an accessible management heading away from routine size selection. This round does not invent new reset or deletion semantics.

## Constraints and specification alignment

Product/UI/Integration adaptive-storage amendments and decisions sections 108–109 govern. Automatic growth, admission checks, physical-space limits, memory/continuity bounds, source removal, protected queues and explicit Pause all retain their existing behavior. A disk increase must not be presented as a remedy for a separate processing or memory bound. Generated Notion mirrors are never hand-edited. If implementation requires a material new semantic or conflicts with governing sources, amend the source first.

Preserve unrelated in-progress workspace changes. README and a new numbered decision entry will record the implemented presentation and scoped verification.

## Verification

Inspect existing storage accessibility tests before implementation. Check both radio choices, conditional custom controls, actual usage versus limits, expandable policy text, the shared preference, and reduction confirmation through accessible semantics. Preserve truthful blocked-storage messages and protected-queue behavior. Use JVM checks for pure presentation where supported; use the repository wrapper for affected device accessibility classes. Run the project's verification skill, JVM suite, assembleDebug and androidTest compilation as applicable. Report human TalkBack speech acceptance separately.

## Builder choices

Exact spacing and concise copy may be refined using existing components. No new dependency, storage algorithm, destination, automatic download, or migration is proposed.
