<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Note Organization
  Page id:     3dec9345-e10a-812d-be61-fc24526f9fd3
  Notion URL:  https://app.notion.com/p/Notion-Note-Organization-3dec9345e10a812dbe61fc24526f9fd3
  Notion last edited: 10/05/2026 02:28:00
  Notion is authoritative. Re-run the exporter after editing there.
-->

# Notion Note Organization

> Governing document, mirrored from Notion. See the header above for provenance.

# Purpose

Use this Skill whenever creating, saving, updating, organizing, or structurally editing notes in this Notion workspace. The goal is consistent retrieval, one authoritative copy of each note, useful relationships without manual overhead, and linear hands-free listening.

# Mandatory execution protocol

When the user asks to save, create, capture, or organize a note, follow these steps in order. Do not skip a step merely because the destination seems obvious.
1. Load this Skill. Treat the current fetched version as the authoritative workflow for the task.
1. Classify the content. Determine its single authoritative home using the routing rules below.
1. Inspect the destination schema. Fetch the target database or data source before creating or updating an entry so exact properties and allowed values are known.
1. Determine metadata before writing. Infer a concise title, Area, Topics, date when relevant, summary, and any destination-specific properties.
1. Deduplicate when applicable. For durable Knowledge, search for a substantially equivalent existing note before creating a new one. Prefer updating the existing note when it represents the same concept.
1. Infer relations. Add an obvious project or related-note relation automatically when the relationship is clear from the current context. If the relationship is uncertain, leave the relation empty. A missing relation must never block note creation.
1. Create or update exactly one authoritative copy. Never duplicate note content merely to make it appear in another project, category, or context. Use metadata and relations instead.
1. Write the body for listening. Use a predictable speech-friendly structure and put useful content before metadata-like material.
1. Fetch the resulting page after creation or any structural update. Do not rely only on the intended write.
1. Verify the result against this Skill. Check the authoritative destination, title, type or category where supported, Area, Topics, date when relevant, summary, relations, and body structure.
1. Repair mismatches before reporting completion. If the resulting page does not match the intended classification or metadata, correct it and fetch again when needed.

Only after verification should the note be reported as successfully organized.

# Current authoritative homes

Until the workspace schema is deliberately migrated, use the current databases as the authoritative homes:
- Inbox — raw, incomplete, or not-yet-classified material.
- Knowledge — durable explanations, concepts, references, how-tos, reusable decisions, and generalized understanding.
- Projects — active outcome-oriented work, implementation specifications, architecture, decisions, milestones, blockers, experiments, and next actions.
- Practice & Learning — dated study sessions, experiments, chess games, music practice, rehearsals, benchmarks, and other practice observations.
- Reflections — personal observations, experience, self-understanding, mindfulness, relationships, and life lessons.
- Tasks — concrete actions to complete.
- Archive — a status or destination for inactive/completed material when the relevant schema supports it.

Do not create a second copy in another database because the note is relevant there. Link it instead when useful.

If the workspace is later migrated to a consolidated Library database for Knowledge, Reflection, and Practice, update this Skill at the same time as the schema migration. Do not assume that migration has occurred merely because it was discussed.

# Routing rules

Use meaning, not keywords alone.
- Raw or unfinished material → Inbox.
- Durable reusable explanation or reference → Knowledge.
- Active work toward a concrete outcome → Projects.
- A dated learning, practice, game, rehearsal, benchmark, or experiment session → Practice & Learning.
- Personal experience or self-observation → Reflections.
- Concrete action to be completed → Tasks.

If one piece of content contains both personal experience and generalized knowledge, preserve the personal experience in Reflections and extract a separate Knowledge note only when the generalized concept is independently useful. Do not merge the two merely because they share a subject.

# Stable Areas

Use broad, stable Areas. Do not create a new Area for every narrow subject.
- AI & ML
- Software Engineering
- Cloud & Infrastructure
- Accessibility
- Music & Audio
- Chess
- Mind & Learning
- Career & Work
- Relationships
- Business & Strategy
- Technology & Devices
- Finance & Personal Systems

Do not use broad catch-all Areas such as Life. Personal material should use the most meaningful stable domain, such as Mind & Learning or Relationships.

Use Topics for narrower concepts such as inference, llama.cpp, Kotlin, Android TTS, Sicilian Defense, business opportunities, unit economics, pricing, singing, rumination, mindfulness, NVDA, workload identity, or quantization.

Area answers: What broad domain is this in?

Topics answer: What specifically is this about?

## Area vocabulary governance

Treat the list above as the canonical Area vocabulary for the workspace.
- A destination database may expose only the subset currently relevant to that database; it does not need every Area pre-created.
- When durable content clearly belongs to a canonical Area that is missing from the destination schema, extend that schema with the canonical Area name rather than forcing the note into a neighboring Area.
- Do not create a new Area when an existing Area plus one or more Topics expresses the subject accurately.
- Add a new Area only when it represents a broad, durable domain expected to contain multiple Topics and multiple notes over time.
- Use the same spelling and meaning for an Area wherever it appears.

# Metadata rules

When the destination supports these properties, populate them as follows:
- Title — concise and descriptive; describe the idea or event rather than using vague titles such as Notes.
- Area — one broad stable Area.
- Topics — only specific topics materially represented by the content. Do not add unrelated tags merely because they are adjacent interests.
- Date — the event/practice/reflection date when chronology matters; otherwise use the schema's normal creation metadata rather than duplicating dates unnecessarily.
- Summary — one or two sentences that make the note understandable in a list or spoken preview.
- Status — use only when the destination schema defines a meaningful lifecycle.
- Relations — optional contextual links, governed by the relation rules below.

Metadata is primarily for discovery, filtering, queue construction, and navigation. Do not repeat all metadata in the spoken body.

# Topic vocabulary rules

Treat Topics as a controlled but extensible vocabulary, not an unrestricted pile of tags.
1. Fetch the destination schema before assigning Topics.
1. Reuse an existing Topic option when it accurately represents the concept.
1. Search existing workspace usage when the canonical wording is uncertain. The same concept should use the same Topic name across databases whenever practical.
1. If a strongly useful Topic does not exist, extend the destination's multi-select schema while preserving every existing option, then assign the new Topic.
1. Add a new Topic only when it is likely to help future retrieval across multiple notes; do not create one-off synonyms, near-duplicates, or overly narrow phrases.
1. Prefer canonical short nouns or noun phrases such as mindfulness, Android TTS, quantization, Sicilian Defense, or unit economics.
1. Do not add a Topic merely because it is mentioned incidentally.
1. When two Topic names are near-synonyms, reuse the established canonical option rather than creating another spelling or phrasing.

Topic creation must not require user confirmation when the concept is clear and the schema can be updated safely. If the right canonical Topic is genuinely ambiguous, omit it rather than inventing taxonomy.

# Relation rules

Relations are useful context, not a prerequisite for saving.
- Add a Project relation automatically when the note was clearly produced while working on that project or is directly required by that project.
- Leave the Project relation empty when the relationship is ambiguous.
- Do not ask the user to manually link a note when the relationship is obvious from the current task.
- Do not invent a relation merely because two pages share a Topic or Area.
- Use Related notes only for a meaningful conceptual dependency, continuation, contrast, prerequisite, or direct reference—not generic similarity.
- Never duplicate content instead of using a relation.
- Missing relations never block note creation.

Example:
- A reusable Android TTS insight discovered while implementing Notion Reader belongs in Knowledge and may automatically relate to the Notion Reader project.
- A mindfulness reflection normally has no Project relation.

# Deduplication rules

Before creating durable Knowledge:
1. Search for the main concept using concise distinguishing terms.
1. If an existing page substantially represents the same concept, fetch it.
1. Prefer extending or updating that page rather than creating another copy.
1. Create a new page when the concept, scope, or purpose is genuinely distinct.

Do not use aggressive deduplication for dated Practice & Learning entries or Reflections when the new entry represents a new event or observation over time.

# Separate experience from generalized knowledge

Keep first-person experience separate from general explanation.

Example:
- Reflection: Thoughts are less reactive but still frequent
- Knowledge: Thought frequency vs thought reactivity

The Reflection records what the user noticed. The Knowledge note explains the reusable concept. Link them only when the connection materially helps later navigation.

# Projects and extracted knowledge

Projects hold active implementation context: goal, current state, architecture, decisions, experiments, blockers, next actions, and related material.

When a project produces a durable insight useful outside that project:
1. Search Knowledge for the concept.
1. Update or create the Knowledge note.
1. Add a Project relation automatically when the project connection is clear and the schema supports it.
1. Keep project-specific status/history in Projects rather than copying it into Knowledge.

# Practice and extracted knowledge

Practice & Learning entries are chronological records. Repeated or generalizable lessons should graduate into Knowledge rather than being copied into every session.

Examples:
- Practice: a specific Sicilian game.
- Knowledge: a recurring positional idea from several Sicilian games.
- Practice: a singing session.
- Knowledge: a reusable explanation of why singing while self-accompanying is harder.

# Reader-friendly body structure

For substantial notes, prefer this spoken order when applicable:
1. Summary
1. Core idea or observation
1. Explanation or what happened
1. Examples, evidence, or details
1. Practical application, lesson, or next action
1. Related notes when they add real value

Do not force empty sections. Keep the useful content near the top. Avoid excessive visual-only formatting and avoid fragmenting one understandable idea into many tiny pages merely for atomicity.

For Reflections, a natural variant is:
1. Summary
1. What I am noticing
1. What I want to remember
1. Current practice or response
1. Practical takeaway

For Practice & Learning, a natural variant is:
1. Session summary
1. What I practiced or tested
1. What happened
1. What I learned
1. Next practice step

# Hands-free retrieval principles

Organize metadata so future readers can construct deterministic queues without depending on folder navigation.

Typical retrieval should be possible through combinations such as:
- destination/type + Area
- destination/type + Topics
- Area + Topics
- date range + destination/type
- Project relation

Chronological order is appropriate for Reflections and Practice. Never treat Knowledge creation order as learning order.

## Learning paths and curriculum maps

When the user is deliberately studying a domain over time and sequence materially improves learning, use an explicit Knowledge note as the curriculum or learning map rather than relying on creation dates.
- The learning-map note should describe the goal, current level, major concepts or stages, recommended sequence, current focus, and next concept to study.
- Keep durable concept explanations in their own canonical Knowledge notes when they are independently useful; the learning map should point to or relate to them rather than duplicate them.
- Keep dated exercises, case discussions, games, rehearsals, experiments, and study sessions in Practice & Learning.
- Update the learning map as understanding changes; do not create one map per session.
- Create a learning map only when there is a genuine multi-step curriculum. Do not add one for isolated questions or a handful of unrelated notes.
- Do not introduce a new database or sequence property merely to represent a learning path unless the existing note-and-relation model becomes insufficient.

# Verification checklist

After creating or structurally updating a page, fetch it and verify all applicable items:
- Correct authoritative database or parent
- Correct concise title
- Correct Area
- Appropriate Topics
- Correct date when chronology matters
- Useful Summary
- Correct obvious Project relation when supported
- No invented or speculative relation
- No duplicate authoritative copy created
- Speech-friendly body order
- No unnecessary metadata repeated in the body

If any applicable item is wrong, repair it before reporting completion.

# Implementation specifications

Treat feature specifications, architecture documents, and engineering plans for active products as Projects content.

Structure them for execution with the relevant subset of:
1. Summary
1. Goals
1. Non-goals
1. User experience
1. Functional requirements
1. Data model
1. Architecture
1. Interfaces
1. State and persistence
1. Error handling
1. Accessibility
1. Security and privacy
1. Test strategy
1. Implementation phases
1. Acceptance criteria
1. Open questions
1. Next implementation step

Do not include sections that are genuinely irrelevant.

# User interaction rule

The user should normally be able to say only save this, create a note, or equivalent. Perform classification, metadata inference, obvious relation inference, creation, and verification automatically.

Ask the user to choose only when ambiguity would materially change the meaning or authoritative home of the information. Do not interrupt note creation merely because an optional relation or Topic is uncertain.

# Automation knowledge-capture protocol

Use this section for recurring ChatGPT worker automations that may persist useful output into Notion.

## Core rule

An automation run is never, by itself, a reason to create or update a note. Persist only information with durable future value. The normal automation response remains the primary delivery mechanism; Notion is the durable knowledge layer.

## Capture decision

After completing the substantive work, classify persistence as one of:
- SKIP — nothing durable enough to preserve.
- UPDATE — durable information belongs in an existing authoritative page.
- CREATE — durable information is genuinely distinct and no suitable authoritative page exists.
- LINK — update the primary authoritative page and add a meaningful relation/reference when another page is materially affected.

Prefer SKIP over low-value capture and UPDATE over CREATE.

## Durable capture threshold

Persist when the result contains one or more of:
- a validated decision and rationale;
- reusable technical or conceptual knowledge;
- a meaningful experiment, benchmark, or result;
- an actionable opportunity worth revisiting;
- a durable personal observation or unresolved question likely to matter again;
- an important correction or superseding fact;
- an architecture, implementation, or project-state change that affects future work.

Do not persist routine status, daily summaries, weak speculation, generic advice, raw search results, duplicated material, or failed leads with no lasting lesson.

## Worker-specific routing
- Discovery & Opportunities: highest capture threshold. Usually SKIP. Capture only an opportunity/development that is sufficiently actionable or decision-relevant to revisit. Reusable explanation belongs in Knowledge; project-specific impact belongs in Projects. Do not create a daily opportunity log unless the user explicitly creates such a system.
- Adaptive Learning: preserve durable generalized understanding in Knowledge. Dated exercises, benchmark sessions, or study observations belong in Practice & Learning. Prefer evolving canonical topic notes over one note per lesson/run.
- Active Context: preserve project-specific architecture, decisions, experiments, blockers, acceptance criteria, and next-state changes in the relevant Projects page. Extract generalized reusable insights into Knowledge only when independently useful.
- Adaptive Reflection: use Reflections selectively for durable first-person observations, recurring tensions, meaningful questions, or experiments worth revisiting. Do not save every reflection run.

## Search-before-write and canonical-state rules
1. Load this Skill and inspect the destination schema before writing.
1. Search for the canonical existing page before creating anything durable.
1. Fetch the candidate page and update it when it owns the concept.
1. Prefer semantic consolidation over chronological append-only logs. Keep the page's best current model up to date.
1. Preserve useful history under sections such as rejected approaches, superseded understanding, decision rationale, or experiment history only when that history itself is valuable.
1. Never create a duplicate merely because another worker discussed the same subject.
1. Use relations/links to connect distinct objects instead of copying content.

## Provenance and uncertainty

Preserve the distinction between:
- fact/evidence — supported by primary documentation, source code, or a reproducible observation;
- inference — a conclusion drawn from available evidence;
- hypothesis — requires validation;
- decision — a chosen direction that may have viable alternatives.

Do not let repeated summaries turn a hypothesis into an apparent fact.

## Experiments and benchmarks

When a durable experiment is captured, include the useful subset of:
- hypothesis;
- configuration/provenance;
- predicted outcome;
- observed result;
- interpretation;
- what the result rules out or supports;
- remaining uncertainty;
- next experiment.

Raw run output need not be copied when a concise reproducible summary is sufficient.

## Decisions

For consequential decisions, preserve the useful subset of:
- context;
- alternatives considered;
- chosen approach;
- rationale/evidence;
- consequences/trade-offs;
- validation required;
- revisit condition.

Project-local decisions normally remain in Projects; broadly reusable decisions may graduate into Knowledge.

## Failure isolation

A Notion failure must never cause an otherwise useful automation run to fail.
- If search or fetch fails, do not blindly create a replacement page.
- If a write is uncertain or fails, do not retry by creating a duplicate.
- Return the substantive automation result and briefly report the persistence failure.
- A successful run with SKIP is fully successful.

## User-facing reporting

If a Notion write succeeds, add one short line at the end of the automation response stating what page was created or updated and what durable information was captured.

If persistence is SKIP, say nothing about Notion.

If persistence fails, state that the substantive result succeeded but Notion persistence did not.

## Governance

The Adaptive Strategy Leader may adjust each worker's expected capture targets and thresholds in its assignment, but it must preserve this protocol. The leader does not require a write in advance because the worker sees the actual result. During periodic audits, the leader should watch for duplicate pages, transient material being persisted, fragmented learning notes, stale canonical knowledge, or cross-worker copying, and correct future capture guidance rather than reorganizing the workspace automatically.

