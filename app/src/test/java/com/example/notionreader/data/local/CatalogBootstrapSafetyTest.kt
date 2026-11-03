package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogBootstrapSafetyTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun corruptInterestsAreNotMistakenForAnEmptyList() = runBlocking {
        val dir = files.newFolder(); val store = FileDiscoveryInterestStore(dir)
        assertEquals(emptyList<DiscoveryInterest>(), store.readForCatalogBootstrap())
        val follow = DiscoveryInterest("id", "seed germination", displayName = "Seeds", originTopicId = "topic")
        store.write(listOf(follow))
        assertEquals(listOf(follow), store.readForCatalogBootstrap())
        File(dir, "interests.json").writeText("{broken")
        assertNull(store.readForCatalogBootstrap())
    }
    @Test fun sourceDeltasAreSafeButOrphanConceptWorkIsNot() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val delta = PassageDeltaRecord("note", "r", EmbeddingIdentity("model", 2), semanticHash("fingerprint"),
            PassageDelta(setOf("p"), emptySet(), emptyMap()))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(delta) { true })
        assertTrue(store.hasOnlySourceDeltas())
        val concept = ProposalConceptRecord(semanticHash("work"), listOf(ProposalEvidenceRef("note", "r", "p")),
            "Seed germination", "Conditions for seeds to germinate.", "Listen to seed germination.", listOf("p"))
        assertEquals(ProposalProgressWriteResult.Written, store.writeConcept(concept) { true })
        assertFalse(store.hasOnlySourceDeltas())
    }
    @Test fun aFollowCannotBeWrittenDuringTheBootstrapDecision() = runBlocking {
        val directory = files.newFolder()
        val store = FileDiscoveryInterestStore(directory)
        val otherInstance = FileDiscoveryInterestStore(directory)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val guard = async { store.withCatalogBootstrapGuard { existing ->
            assertEquals(emptyList<DiscoveryInterest>(), existing)
            entered.complete(Unit); release.await()
        } }
        entered.await()
        val follow = DiscoveryInterest("id", "seeds", originTopicId = "topic")
        val started = CompletableDeferred<Unit>()
        val writer = async { started.complete(Unit); otherInstance.write(listOf(follow)) }
        started.await()
        val completedEarly = withTimeoutOrNull(1_000) { writer.await(); true } ?: false
        assertFalse("A follow write must wait for the decision and catalog commit", completedEarly)
        release.complete(Unit); guard.await(); writer.await()
        assertEquals(listOf(follow), store.readForCatalogBootstrap())
    }

}
