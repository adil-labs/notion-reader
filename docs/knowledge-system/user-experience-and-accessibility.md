# Reader journeys and accessibility

Status: proposed UX contract, subordinate to the governing [UI screens and navigation](../notion/ui-screens-and-navigation-contract.md) and existing playback specifications.

## Destinations
**Browse:** original workspace hierarchy, database/page relationships, breadcrumb trail, sections and explicit links; no AI required.
**Explore:** existing canonical Discovery topics with named concepts, status, evidence and matching Notes; do not mix topic seeds with validated matches.
**Search:** titles, text and semantics with exact relevant passages, source labels and direct-open actions.
**Read / Listen:** section-aware reading, related passages and maintained queues/progress; do not move/rewrite source Notes.

## Representative journeys
1. "I remember a phone-model memory experiment but not its title" -> search natural language -> inspect excerpt -> open correct section.
2. Read a KV-cache section -> inspect explicitly related Notes and separately labeled inferred suggestions -> open supporting passage.
3. Browse a nested database -> inspect page parent, section and backlink -> return with focus restored.
4. Create a multi-Note listening queue -> resume midsection after an app restart or Note move.
5. Delete or revoke a source during discovery -> previously pinned suggestion is withdrawn without stale spoken content.

## Accessibility invariants
Compose semantics provide concise unique row labels, section headings, actionable relationships, announced publication/coverage status, stable traversal order, and predictable Back/focus restoration. Rendering or background updates never steal focus; use lazy lists with stable keys for large catalogs. Ensure all paths operable with TalkBack and external keyboard/screen reader. An inferred edge must be spoken as "suggested", unlike an explicit relation.

## State and errors
Distinguish offline cache freshness, indexing, naming unavailable, partial topic matches, no relevant passages, inaccessible target and retriable error. Avoid busy announcements and fake percentages. Opening a page/section must work while generator is absent, paused or cancelled. Preserve player controls and audio focus according to existing contracts.

## Test matrix
Screen-reader speech/focus journeys, large catalog virtualization, dynamic update during keyboard navigation, route/deep link to obsolete passage, heading repetition, inactive model, offline restart, player in background, reset and multiple matching topics. Record real TalkBack observations separately from Compose semantics unit tests.
