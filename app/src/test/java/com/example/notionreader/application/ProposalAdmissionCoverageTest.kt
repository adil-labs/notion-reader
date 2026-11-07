package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import org.junit.Assert.*
import org.junit.Test

class ProposalAdmissionCoverageTest {
    @Test fun verifiedCitationsMayPublishEmergingBeforeSourceScanCompletes() {
        val coverage = ProposalAdmissionCoverage(sourceScanComplete = false, catalogConsistent = true,
            duplicateCheckComplete = true)
        assertTrue(coverage.allowsEmerging(citationsSupported = true))
        assertFalse("Established still requires complete source coverage", coverage.allowsEstablished(true))
        assertFalse("An unverified citation is never admissible", coverage.allowsEmerging(false))
    }

    @Test fun unresolvedOrStaleEvidenceAndInconsistentCatalogBlockEmerging() {
        assertFalse(ProposalAdmissionCoverage(unresolvedEvidence = true).allowsEmerging(true))
        assertFalse(ProposalAdmissionCoverage(staleEvidence = true).allowsEmerging(true))
        assertFalse(ProposalAdmissionCoverage(catalogConsistent = false).allowsEmerging(true))
        assertFalse(ProposalAdmissionCoverage(duplicateCheckComplete = false).allowsEmerging(true))
    }

    @Test fun onlyEvidenceInvalidatingReasonsAreUnsafeForPersistedConcepts() {
        for (reason in ProposalIncompleteReason.entries) {
            val unsafe = reason == ProposalIncompleteReason.STALE_SOURCE ||
                reason == ProposalIncompleteReason.EXISTING_CONCEPT_UNCERTAIN
            assertEquals("$reason safety classification", unsafe, reason.invalidatesVerifiedEvidence())
        }
        assertTrue(ProposalIncompleteReason.SOURCE_SCAN.blocksConceptAdmission())
        assertFalse(ProposalIncompleteReason.STRUCTURE.blocksConceptAdmission())
    }
}
