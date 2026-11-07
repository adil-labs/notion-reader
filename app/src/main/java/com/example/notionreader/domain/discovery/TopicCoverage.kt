package com.example.notionreader.domain.discovery

/**
 * Coverage facts derived from a terminal Note-concept scan.
 *
 * These are three independent observations, never one status:
 * - [supported] references currently verified evidence;
 * - [uncheckedRevisions] Notes that stopped after verified support and still hold a candidate tail;
 * - [uncertainRevisions] Notes whose terminal scan left unresolved evidence for this concept.
 *
 * [matchingComplete] means every eligible Note reached a resolved terminal assessment. It does not
 * mean every Note supports the concept, and it is false while any Note is unresolved.
 */
internal data class ConceptCoverageUpdate(
    val supported: List<ProposalEvidenceRef>,
    val scannedRevisions: Map<String, String>,
    val uncheckedRevisions: Map<String, String>,
    val uncertainRevisions: Map<String, String>,
    val matchingComplete: Boolean
)

/**
 * Pure reconciliation of one Note's terminal scan into a concept's durable coverage.
 *
 * A Note may simultaneously contribute verified support, prior uncertainty and an unchecked tail.
 * Valid evidence is never retracted by a later inconclusive observation. A source that disappeared
 * or changed revision is withdrawn rather than carried forward.
 *
 * @param sources current accepted source revisions (the coverage universe)
 * @param fullCoverage true only when [sources] covers every eligible indexed Note
 * @param earlyWorkKeys concepts this Note left an unchecked tail for
 */
internal fun reconcileConceptCoverage(concept: CanonicalConceptEvidence, noteId: String, noteRevision: String,
    sources: Map<String, String>, fullCoverage: Boolean, assessments: List<TopicSupportAssessment>,
    earlyWorkKeys: Set<String>): ConceptCoverageUpdate {
    val forConcept = assessments.filter { it.conceptWorkKey == concept.workKey && it.evidence.pageId == noteId }
    val supportedHere = forConcept.filter { it.decision == SupportDecision.SUPPORTED }.map { it.evidence }
    val uncertainHere = forConcept.any { it.decision == SupportDecision.UNCERTAIN }
    val scanned = concept.scannedRevisions.filter { (page, revision) -> sources[page] == revision } + (noteId to noteRevision)
    // Retain every still-current supported reference, including this Note's own verified support.
    val supported = (concept.supported.filter { scanned[it.pageId] == it.revision ||
        it.pageId !in scanned && sources[it.pageId] == it.revision } + supportedHere)
        .distinct().sortedWith(compareBy<ProposalEvidenceRef> { it.pageId }.thenBy { it.passageId })
    val unchecked = concept.uncheckedRevisions
        .filter { (page, revision) -> page != noteId && sources[page] == revision }.toMutableMap()
    if (concept.workKey in earlyWorkKeys) unchecked[noteId] = noteRevision else unchecked.remove(noteId)
    val uncertain = concept.uncertainRevisions
        .filter { (page, revision) -> page != noteId && sources[page] == revision }.toMutableMap()
    if (uncertainHere) uncertain[noteId] = noteRevision else uncertain.remove(noteId)
    // Coverage is complete only when the whole eligible corpus is scanned and nothing is unresolved.
    // Which Notes support the concept is a different fact and never an input to this decision.
    val matchingComplete = fullCoverage && scanned.keys.containsAll(sources.keys) && uncertain.isEmpty()
    return ConceptCoverageUpdate(supported, scanned, unchecked, uncertain, matchingComplete)
}
