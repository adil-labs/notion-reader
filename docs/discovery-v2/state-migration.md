# Discovery v2 — persisted state, migration, source safety

> **Current direction:** The local Gemma 4 E2B / LiteRT-LM replacement and requirement dispositions are defined in [generative-direction.md](generative-direction.md) under the amended governing Notion contracts. Deterministic-only, literal-label-only and activity-ranking passages in this original proposal are superseded where they conflict. This file preserves the original proposal for comparison; it is not the execution authority.

**Status:** proposal. Do not edit production storage schema or reset user discoveries based on this document without governing-contract approval and migration tests.

## 1. Proposed data contract

Reuse existing domain structures where feasible: SemanticPassage, EmbeddedPassage, SemanticRecord, TopicMembership, DiscoveredTopic, TopicContinuity, TopicSnapshot, SubjectRelationship and TopicStore. Add fields by versioned migration, **not** by silently repurposing current score/label semantics.

~~~kotlin
// Illustrative domain contract, not compiled production code.
data class ConceptFeature(
    val value: Float?,       // null = UNKNOWN, not an implicit zero or one
    val reasonCodes: List<String>,
    val evidenceIds: List<String>,
    val featureVersion: Int
)
data class ConceptQuality(
    val policyVersion: Int,
    val modelIdentity: String,
    val features: Map<String, ConceptFeature>,
    val score0to100: Float?,  // null on required unknown/invalid evidence
    val hardGateFailures: List<String>
)
enum class ConceptDecision {
    UNMATCHED, PROVISIONAL, SUPPRESSED, EMERGING, ESTABLISHED
}
data class CandidateRef(
    val id: String,
    val memberPassageIds: Set<String>,
    val memberPageIds: Set<String>,
    val sourceRevisions: Map<String,String>,
    val labelSourcePassageId: String?,
    val label: String?,
    val quality: ConceptQuality,
    val decision: ConceptDecision,
    val explanationCodes: List<String>,
    val candidateGeneration: Long
)
~~~

State should store references, compact hashes/normalized vectors when necessary, configurable weight-policy ID, corpus DF statistics version and source identity. Preserve the original source in the reader's single authorized cache/index rather than copying entire private excerpts into candidate/event analytics. Debug/export facilities must scrub or explicitly require consent to include text.

## 2. Stable topic identity and user's explicit state

- Existing published topic identity derives from source-supported continuity, not fuzzy label string.
- On stable evidence revisions, preserve topic ID, followed-interest origin, user custom display name and reader navigation targets.
- **Split:** reserve original identity for the strongest surviving evidence branch; generate new IDs for independent new branches; preserve follow/rename only on a justified mapped branch, otherwise flag review.
- **Merge:** select stable survivor deterministically based on source overlap, creation order and explicit user state; retain alias/redirection for replaced IDs and avoid duplicate follows. Do not combine unrelated narrow subjects because they share Note membership.
- **Suppressed/provisional:** store minimal reason and source references, but do not present as published topics or keep revoked excerpts. A policy change can make a suppressed candidate eligible again after revalidation.
- Parent/child and related edges must have independent affirmative passage evidence, current revision, no self-links and no cycles. Topic identifiers remain independent of presentation position.

## 3. Source invalidation must be immediate

For permission denial, deletion, cache removal, model reset or content revision:
1. Withdraw affected source from visible topics, source excerpts, label sources, membership anchors, Note connections, subject relationships, previews, pending candidate pool and saved checkpoints before admitting new publication.
2. Drop stale scoring features, old body-only embedding references, metadata caches and continuity source hashes that could retain sensitive evidence.
3. Atomically persist a generation without revoked sources when possible; if a write fails, fail **closed** for display and report recovery required. Never continue showing a stale inspected snapshot.
4. Rebuild only current compatible evidence from the authorized cache; do not fetch remote full workspace to reconstruct missing content.
5. Check in-flight reads/worker commits against generation epoch and source revisions so canceled work cannot reintroduce deleted text.

This extends existing invalidateTopics / validity protections, not a replacement of their contract.

## 4. Schema and migration protocol

- Allocate new topic/score/candidate **schema version** and new scoring policy version; maintain explicit provider/model/tokenizer/version identifiers, with source-revision and corpus-IDF dependency checks.
- During rollout, keep v1 read-only baseline for shadow scoring; no visible reinterpretation. Store v2 candidates in separate bounded shadow storage, indexed by current source identities and removable on revocation.
- Only after passing release gates, amend governing source of truth, implement an idempotent migration under an atomic manifest. Unsupported old derived data can be recomputed from current embeddings; **do not delete user queues, playback state, explicit interests or other unrelated storage**.
- Retain an explicit rollback path that disables v2 publication and returns to still-valid v1 behavior where permitted; reject stale v1 data if a newer source/model revision makes it invalid. Do not preserve a second permanently selectable production engine without explicit contract approval.
- Crash at any migration phase: restart validates schema, manifests, source map, complete/incomplete status and checksums; never presents a half-migrated generation. Insufficient space yields an observable pending/recovery state.
- Mixed-model embeddings are never compared without retraining/re-embedding and recalibration; missing activity data is not imputed as hidden personal interest.

## 5. Topic quality and user-facing inspectability

For each published topic expose a stable label, Emerging/Established, member Note count, representative source excerpts, original Note references, optional broader/related subjects, “Why this topic?” explanation derived from concrete reason codes, and incomplete-coverage warning. The UI may present the detailed numeric feature trace in an accessible expandable section. Never call quality 82/100 “82% accurate.” Avoid showing technical source hashes to users or speaking large diagnostic vectors.

Suppression explanations are developer diagnostics; do not create a distracting list of hundreds of rejected topics in the primary UI.

## 6. Compatibility tests

- Persist/reload exact matching source and policy; old generation rejected on model/tokenizer/policy/source mismatch.
- Migration interruption at every write/commit boundary; no half-generation UI.
- Split/merge with followed interest, renamed topic, bookmarked detail navigation; no duplicate/incorrect redirect.
- Removed source appears as label, member, anchor, relationship, pending candidate or checkpoint: all copies and exposures are retired, including inspected views and late callbacks.
- Incomplete processing and unavailable model remain distinct from an empty library.
- Existing named-reference connections, semantic search, queues, session and TTS continue unchanged.

See [requirements](requirements.md), [architecture](architecture.md), and [evaluation](evaluation-acceptance.md).