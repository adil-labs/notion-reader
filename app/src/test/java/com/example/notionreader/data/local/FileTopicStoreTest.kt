package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.*

class FileTopicStoreTest {
    @Test fun malformedEnvelopeSnapshotsCannotBecomeSuccessfulEmptyStores() = runBlocking {
        for (invalidSnapshot in listOf(JsonNull, buildJsonObject { put("schemaVersion", -1) })) {
            for (keepCatalog in listOf(true, false)) {
                val directory = temporary.newFolder()
                val original = FileTopicStore(directory)
                assertTrue(original.write(TopicSnapshot(connections = connections(3))))
                val file = File(directory, "topics.json")
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                val fields = if (keepCatalog) root else root.filterKeys { it !in setOf("catalog", "catalogKey") }
                file.writeText(JsonObject(fields + ("snapshot" to invalidSnapshot)).toString())
                assertNull("Explicit null is corrupt, not a legacy snapshot", FileTopicStore(directory).readMetadata())
            }
        }
    }

    @Test fun openingTheStoreRetainsCatalogPagesWithoutDeserializingTheTopicGraph() = runBlocking {
        val directory = temporary.newFolder()
        val first = FileTopicStore(directory)
        assertTrue(first.write(TopicSnapshot(connections = connections(3))))
        val file = File(directory, "topics.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val snapshot = root.getValue("snapshot").jsonObject
        // The envelope metadata is readable even when a topic body needs recovery.
        file.writeText(JsonObject(root + ("snapshot" to JsonObject(snapshot +
            ("topics" to JsonPrimitive("invalid topic body"))))).toString())
        val pages = directory.listFiles()!!.filter { it.name.startsWith("connections-") }.map { it.name }.toSet()
        val restarted = FileTopicStore(directory)
        assertEquals("Opening a store must not decode topic bodies to identify committed catalog pages",
            pages, directory.listFiles()!!.filter { it.name.startsWith("connections-") }.map { it.name }.toSet())
        assertNull("The explicit read still validates the full body", restarted.readMetadata())
        assertFalse(directory.listFiles()!!.any { it.name.startsWith("connections-") })
    }

    @Test fun cancellingAnUnchangedCatalogScanStopsReadingAndPreservesItsPages() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val pairs = connections(145)
        val model = EmbeddingIdentity("scan-cancellation", 2)
        assertTrue(store.write(TopicSnapshot(connections = pairs, identity = model)))
        val metadata = store.readMetadata()!!
        val job = Job()
        var reads = 0
        val revisions = object : Map<String, String> by emptyMap() {
            override fun get(key: String): String {
                if (++reads == 2) job.cancel()
                return "r"
            }
        }
        try {
            withContext(job) { store.reconcileCatalogs(revisions, model, emptyMap()) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue("Read-only reconciliation must stop within one page; read $reads endpoints", reads <= 128)
        assertEquals(metadata.catalogReference, store.readMetadata()!!.catalogReference)
        assertTrue(store.connectionPage(metadata, 140, 5).available)
    }
    @Test fun legacyAdaptersCannotMistakeAPreviewForTheCompleteReferencedCatalog() = runBlocking {
        val snapshot = TopicSnapshot(connections = connections(2), catalogReference = ConnectionCatalogReference("opaque", 145))
        val adapter = object : TopicStore {
            override suspend fun read() = snapshot
            override suspend fun write(snapshot: TopicSnapshot) = true
            override suspend fun clear() = Unit
            override suspend fun bytes() = 0L
        }
        assertFalse(adapter.connectionPage(snapshot, 0, 20).available)
        assertFalse(adapter.findConnection(snapshot, snapshot.connections.first().id).available)
        try {
            adapter.connectionCatalog(snapshot)
            fail("A legacy adapter must not truncate a referenced catalog to its preview")
        } catch (_: IllegalStateException) { }
    }
    @Test fun progressOnlyWritesReuseUnchangedCatalogPages() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        assertTrue(store.write(TopicSnapshot(connections = connections(145), generation = 1)))
        val metadata = store.readMetadata()!!
        store.pinInspection(metadata)
        val files = directory.listFiles()!!.filter { it.name.startsWith("connections-") }.map { it.name }.toSet()
        val working = store.connectionCatalog(metadata)
        assertTrue(store.write(metadata.copy(connections = working, catalogReference = null, generation = 2)))
        assertEquals(files, directory.listFiles()!!.filter { it.name.startsWith("connections-") }.map { it.name }.toSet())
        assertEquals(metadata.catalogReference, store.readMetadata()!!.catalogReference)
    }

    @Test fun olderPagedCatalogsRecoverReferenceCountsAndOrderingWithoutMaterializingTheCatalog() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        val ordinary = connections(3)
        val seed = ordinary.first()
        val text = "See Target."
        val reference = seed.copy(id = "reference", score = 0f, independentNamedReference = true,
            left = seed.left.copy(metadata = SemanticMetadata("Source"), passage = seed.left.passage.copy(excerpt = text, input = text, end = text.length)),
            right = seed.right.copy(metadata = SemanticMetadata("Target")))
        assertTrue(store.write(TopicSnapshot(connections = ordinary + reference, identity = EmbeddingIdentity("legacy-pages", 2))))
        val file = File(directory, "topics.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val manifest = root.getValue("catalog").jsonObject
        val oldPages = manifest.getValue("pages").jsonArray.map { page ->
            JsonObject(page.jsonObject.filterKeys { it != "pairIds" })
        }
        val oldManifest = JsonObject(manifest.filterKeys { it !in setOf("namedReferences", "ranked", "identity") } +
            ("pages" to JsonArray(oldPages)))
        file.writeText(JsonObject(root.filterKeys { it != "catalogKey" } + ("catalog" to oldManifest)).toString())
        val restarted = FileTopicStore(directory)
        val metadata = restarted.readMetadata()!!
        assertEquals(2, metadata.connections.size)
        assertEquals(1, metadata.catalogReference!!.namedReferences)
        assertEquals(4, metadata.catalogReference!!.count)
        assertTrue(restarted.connectionCatalog(metadata) is RankedConnectionCatalog)
        assertEquals(listOf(reference), restarted.findConnection(metadata, "reference").connections)
    }
    @Test fun cancellationDuringCatalogValidationStopsReadingAndPreservesThePreviousGeneration() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val prior = TopicSnapshot(generation = 1)
        assertTrue(store.write(prior))
        val job = Job()
        var reads = 0
        val seed = connections(1).single()
        val pairs = object : AbstractList<NoteConnection>() {
            override val size = 10_000
            override fun get(index: Int): NoteConnection {
                if (++reads == 64) job.cancel()
                val left = seed.left.passage.copy(id = "a$index:p", pageId = "a$index")
                val right = seed.right.passage.copy(id = "b$index:p", pageId = "b$index")
                return seed.copy(id = "pair$index", left = seed.left.copy(pageId = left.pageId, passage = left, anchor = right),
                    right = seed.right.copy(pageId = right.pageId, passage = right, anchor = left))
            }
        }
        try {
            withContext(job) { store.writeDetailed(TopicSnapshot(connections = pairs, generation = 2)) }
            fail("Cancelled validation must propagate cancellation")
        } catch (_: CancellationException) {
            assertTrue("Validation must stop within 64 subsequent rows; read $reads", reads <= 65)
        }
        assertEquals(prior, store.read())
    }
    @Test fun failedMandatoryWithdrawalCannotLeavePrivateCatalogPagesOnDisk() = runBlocking {
        val directory = temporary.newFolder()
        var failStorage = false
        val model = EmbeddingIdentity("withdrawal-failure", 2)
        val store = FileTopicStore(directory, otherBytes = {
            if (failStorage) throw java.io.IOException("Storage unavailable") else 0L
        })
        val pairs = connections(145)
        assertTrue(store.write(TopicSnapshot(connections = pairs, identity = model)))
        val inspected = store.readMetadata()!!
        store.pinInspection(inspected)
        val eligible = pairs.flatMap { listOf(it.left.pageId, it.right.pageId) }.associateWith { "r" } - "a144"
        failStorage = true
        assertFalse(store.reconcileCatalogs(eligible, model, eligible.keys.associateWith { it }))
        assertEquals("Failed withdrawal must not strand removed private evidence", 0L, store.bytes())
        assertFalse(store.connectionPage(inspected, 0, 20).available)
        assertNull(store.readMetadata())
    }
    @Test fun metadataKeepsOnlyAPreviewWhileItsReferenceOpensTheCompleteCatalog() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val pairs = connections(145)
        assertTrue(store.write(TopicSnapshot(connections = pairs)))
        val metadata = store.readMetadata()!!
        assertTrue("UI snapshots must not own all catalog evidence", metadata.connections.size <= 2)
        assertEquals(145, metadata.catalogReference!!.count)
        assertEquals(pairs.subList(60, 80), store.connectionPage(metadata, 60, 20).connections)
        assertEquals(listOf(pairs.last()), store.findConnection(metadata, pairs.last().id).connections)
    }

    @Test fun aPinnedCatalogSurvivesPublicationAndIsRetiredOnlyAfterRefresh() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val pairs = connections(145)
        assertTrue(store.write(TopicSnapshot(connections = pairs, generation = 1)))
        val old = store.readMetadata()!!
        assertTrue(store.pinInspection(old))
        assertTrue(store.write(TopicSnapshot(connections = pairs.take(70), generation = 2)))
        assertEquals(pairs.subList(130, 145), store.connectionPage(old, 130, 20).connections)
        assertTrue(store.pinInspection(store.readMetadata()!!))
        assertFalse("A released generation must not remain queryable", store.connectionPage(old, 0, 20).available)
        assertEquals(70, store.readMetadata()!!.catalogReference!!.count)
    }

    @Test fun sourceRemovalPurgesHiddenPairsFromLatestAndPinnedCatalogs() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        val model = EmbeddingIdentity("catalog", 2)
        val pairs = connections(145)
        assertTrue(store.write(TopicSnapshot(connections = pairs, generation = 1, identity = model)))
        val old = store.readMetadata()!!
        store.pinInspection(old)
        assertTrue(store.write(TopicSnapshot(connections = pairs, generation = 2, identity = model)))
        val latest = store.readMetadata()!!
        val eligible = pairs.flatMap { listOf(it.left.pageId, it.right.pageId) }.associateWith { "r" } - "a144"
        assertTrue(store.reconcileCatalogs(eligible, model, eligible.keys.associateWith { it }))
        assertEquals(144, store.connectionPage(old, 140, 20).totalCount)
        assertTrue(store.findConnection(old, "pair144").connections.isEmpty())
        assertTrue(store.findConnection(latest, "pair144").connections.isEmpty())
        assertEquals(144, store.refreshCatalogReference(latest).catalogReference!!.count)
        assertTrue("Withdrawn endpoint evidence must also leave every retained on-disk generation",
            directory.listFiles()!!.filter { it.isFile }.none { it.readText().contains("\"a144\"") })
    }

    @Test fun workerCatalogAccessEvictsDecodedPagesInsteadOfRetainingEveryPair() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        assertTrue(store.write(TopicSnapshot(connections = connections(145))))
        val metadata = store.readMetadata()!!
        val worker = store.connectionCatalog(metadata)
        val first = worker[0]
        assertSame(first, worker[0])
        assertEquals("pair130", worker[130].id)
        val reloaded = worker[0]
        assertEquals(first, reloaded)
        assertNotSame("An earlier decoded page must be evicted, not retained in a whole-catalog cache", first, reloaded)
        assertEquals(2, metadata.connections.size)
    }

    @Test fun unavailableCatalogPagesDoNotTurnIntoPartialSuccessfulResults() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        assertTrue(store.write(TopicSnapshot(connections = connections(145))))
        val metadata = store.readMetadata()!!
        directory.listFiles()!!.first { it.name.startsWith("connections-") && it.name.endsWith("-0.json") }.writeText("{corrupt")
        val page = store.connectionPage(metadata, 0, 100)
        assertFalse(page.available)
        assertTrue(page.connections.isEmpty())
        assertEquals("The UI owns its small preview; it must not perform hidden file I/O", 2, metadata.connections.size)
    }

    @Test fun hiddenNamedReferencesAreRevalidatedWhenAnotherEligibleTitleBecomesAmbiguous() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val model = EmbeddingIdentity("named-catalog", 2)
        val ordinary = connections(145)
        val seed = ordinary.first()
        val text = "See Target."
        val reference = seed.copy(id = "reference", score = 0f, independentNamedReference = true,
            left = seed.left.copy(metadata = SemanticMetadata("Source"), passage = seed.left.passage.copy(excerpt = text, input = text, end = text.length)),
            right = seed.right.copy(metadata = SemanticMetadata("Target")))
        assertTrue(store.write(TopicSnapshot(connections = ordinary + reference, identity = model)))
        val inspected = store.readMetadata()!!
        assertEquals(1, inspected.catalogReference!!.namedReferences)
        assertFalse(inspected.connections.any { it.independentNamedReference })
        val sources = ordinary.flatMap { listOf(it.left.pageId, it.right.pageId) }.associateWith { "r" } + ("duplicate" to "r")
        val titles = sources.keys.associateWith { it }.toMutableMap().apply {
            put("a0", "Source"); put("b0", "Target"); put("duplicate", "Target")
        }
        assertTrue(store.reconcileCatalogs(sources, model, titles))
        assertTrue(store.findConnection(inspected, "reference").connections.isEmpty())
        assertEquals(145, store.refreshCatalogReference(inspected).catalogReference!!.count)
    }
    @Test fun targetProgressIsBoundedBySweepSourcesRatherThanOnlySuccessfullyIndexedNotes() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val valid = TopicSnapshot(connectionTargetCursor = 256, connectionTargetCount = 540,
            acceptedRevisions = mapOf("indexed" to "r"))
        assertTrue("Missing/unindexed targets still occupy durable sweep positions", store.write(valid))
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidSnapshotProgress),
            store.writeDetailed(valid.copy(connectionTargetCursor = 541)))
        assertEquals(valid, store.read())
    }
    private fun connections(count: Int): List<NoteConnection> = (0 until count).map { n ->
        fun member(id: String): TopicMembership {
            val text = "Forest ecosystems protect woodland soil and provide habitat for local wildlife."
            val p = SemanticPassage("$id:p", id, "$id:s", "Ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
            return TopicMembership(id, SemanticMetadata(id), p, .9f, p, "r")
        }
        NoteConnection("pair$n", member("a$n"), member("b$n"), .9f)
    }

    @Test fun connectionCatalogPersistsInPagesAndSupportsDirectPageReads() = runBlocking {
        val directory = temporary.newFolder()
        val snapshot = TopicSnapshot(connections = connections(145), generation = 7, complete = true)
        val store = FileTopicStore(directory)
        assertTrue(store.write(snapshot))
        assertTrue("The topic manifest must not embed the catalog excerpts",
            !File(directory, "topics.json").readText().contains("Forest ecosystems"))
        assertEquals(snapshot.connections.subList(60, 80), store.readConnections(60, 20))
        assertEquals(snapshot, FileTopicStore(directory).read())
        assertTrue(store.readConnections(145, 20).isEmpty())
    }

    @Test fun missingCatalogPageCannotPublishAPartialGeneration() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        assertTrue(store.write(TopicSnapshot(connections = connections(70), generation = 7)))
        val page = directory.listFiles()!!.first { it.name.startsWith("connections-") }
        assertTrue(page.delete())
        assertNull(FileTopicStore(directory).read())
        assertEquals(0L, store.bytes())
    }

    @Test fun budgetRefusalPreservesThePriorCatalogAndRemovesStagingPages() = runBlocking {
        val directory = temporary.newFolder()
        val prior = TopicSnapshot(connections = connections(70), generation = 7)
        val store = FileTopicStore(directory)
        assertTrue(store.write(prior))
        val before = directory.listFiles()!!.map { it.name }.toSet()
        val limited = FileTopicStore(directory, store.bytes() + 1)
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded),
            limited.writeDetailed(prior.copy(generation = 8, connections = connections(145))))
        assertEquals(prior, store.read())
        assertEquals(before, directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun interruptedUncommittedCatalogPagesAreRemovedOnRestart() = runBlocking {
        val directory = temporary.newFolder()
        val prior = TopicSnapshot(connections = connections(70), generation = 7)
        assertTrue(FileTopicStore(directory).write(prior))
        val orphan = File(directory, "connections-00000000-0000-0000-0000-000000000000-0.json")
        orphan.writeText("uncommitted private evidence")
        assertEquals(prior, FileTopicStore(directory).read())
        assertFalse("An interrupted write must not strand private staging or consume the budget", orphan.exists())
    }
    @Test fun allSupportedSubjectsRemainPersistableWithoutAnAnchorLimit() = runBlocking {
        val identity = EmbeddingIdentity("many-subjects", 70)
        val records = (0 until 70).map { n ->
            val heading = "Subject number $n"
            val p = SemanticPassage("p$n", "n$n", "s$n", heading, "b$n", "c$n", 0, 80,
                "$heading has independent explanatory body evidence.", "$heading body", 0)
            SemanticRecord("n$n", "r1", identity, SemanticMetadata("n$n"),
                listOf(EmbeddedPassage(p, List(70) { if (it == n) 1f else 0f })))
        }
        val snapshot = SectionHierarchicalEngine(identity).discover(records, null, TopicPolicy())
        assertEquals(70, snapshot.topics.size)
        val store = FileTopicStore(temporary.newFolder())
        assertEquals(TopicWriteResult.Written, store.writeDetailed(snapshot))
        assertEquals(snapshot, store.read())
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidIdentity),
            store.writeDetailed(snapshot.copy(engineVersion = 0)))
    }

    @Test fun malformedFormationCheckpointCannotReplaceThePreviousGeneration() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val prior = TopicSnapshot(generation = 4)
        assertTrue(store.write(prior))
        val corrupt = prior.copy(engineVersion = SectionHierarchicalEngine.VERSION,
            formationProgress = TopicFormationProgress("signature", 2, listOf(listOf(0), listOf(0))))
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidSnapshotProgress),
            store.writeDetailed(corrupt))
        assertEquals(prior, store.read())
    }
    @Test fun revisedCheckpointBranchesRemainPersistableWithoutReusingAnIdentity() = runBlocking {
        val model = EmbeddingIdentity("checkpoint-branches", 2)
        fun record(page: String, axis: Int, revision: String = "r1"): SemanticRecord {
            val text = if (axis == 0) "Forest ecosystems contain diverse trees" else "Planetary astronomy studies distant stars"
            val passage = SemanticPassage("$page:body", page, "$page:section", null, "$page:block", "$page:chunk",
                0, text.length, text, text, 0)
            return SemanticRecord(page, revision, model, SemanticMetadata(page),
                listOf(EmbeddedPassage(passage, if (axis == 0) listOf(1f, 0f) else listOf(0f, 1f))))
        }
        val initial = SectionHierarchicalEngine(model).step(listOf(record("a", 0), record("b", 1)), null, TopicPolicy(), 1)
        val store = FileTopicStore(temporary.newFolder())
        assertEquals(TopicWriteResult.Written, store.writeDetailed(initial))
        val revised = SectionHierarchicalEngine(model).step(listOf(record("a", 1, "r2"), record("b", 1), record("c", 0)), initial, TopicPolicy(), 1)
        assertEquals(TopicWriteResult.Written, store.writeDetailed(revised))
        assertEquals(revised, store.read())
    }
    @Test fun invalidSnapshotsAreNotReportedAsBudgetExhaustion() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        val prior = TopicSnapshot(generation = 4)
        assertTrue(store.write(prior))
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidIdentity),
            store.writeDetailed(prior.copy(schemaVersion = -1)))
        assertEquals(prior, store.read())
    }
    @Test fun peakBudgetFailureHasAnExplicitOutcome() = runBlocking {
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded),
            FileTopicStore(temporary.newFolder(), 1).writeDetailed(TopicSnapshot()))
    }
    @Test fun ioFailurePreservesThePreviousGeneration() = runBlocking {
        val directory = temporary.newFolder()
        val prior = TopicSnapshot(generation = 4)
        assertTrue(FileTopicStore(directory).write(prior))
        val store = FileTopicStore(directory, otherBytes = { throw java.io.IOException("private details") })
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.IoFailure), store.writeDetailed(prior.copy(generation = 5)))
        assertEquals(prior, store.read())
    }
    @Test fun cancellationIsNotConvertedToAWriteFailure() = runBlocking {
        val store = FileTopicStore(temporary.newFolder(), otherBytes = { throw kotlinx.coroutines.CancellationException("cancelled") })
        try { store.writeDetailed(TopicSnapshot()); fail("Cancellation must propagate") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun atomicWriteIoFailureIsReportedWithoutLeavingStagingFiles() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        File(directory, "topics.json").mkdir()
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.IoFailure), store.writeDetailed(TopicSnapshot()))
        assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
    }
    @Test fun legacyBooleanAdaptersReportUnknownWithoutInventingACause() = runBlocking {
        val legacy = object : TopicStore {
            override suspend fun read(): TopicSnapshot? = null
            override suspend fun write(snapshot: TopicSnapshot) = false
            override suspend fun clear() { }
            override suspend fun bytes() = 0L
        }
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.Unknown), legacy.writeDetailed(TopicSnapshot()))
    }
    @get:Rule val temporary = TemporaryFolder()
    @Test fun aCompletedSnapshotSurvivesRestart() = runBlocking {
        val directory = temporary.newFolder()
        val snapshot = TopicSnapshot(continuity = listOf(TopicContinuity("opaque", 1, setOf("page"))), generation = 3, complete = true)
        assertTrue(FileTopicStore(directory).write(snapshot))
        assertEquals(snapshot, FileTopicStore(directory).read())
    }
    @Test fun insufficientPeakBudgetPreservesPreviousGeneration() = runBlocking {
        val directory = temporary.newFolder()
        val snapshot = TopicSnapshot(generation = 1, complete = true)
        val store = FileTopicStore(directory)
        assertTrue(store.write(snapshot))
        val priorBytes = store.bytes()
        assertFalse(FileTopicStore(directory, priorBytes + 1).write(snapshot.copy(generation = 2)))
        assertEquals(1L, store.read()!!.generation)
    }
    @Test fun corruptSnapshotIsDiscardedWithoutPublishingEvidence() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "topics.json").writeText("{broken")
        assertNull(FileTopicStore(directory).read())
        assertEquals(0L, FileTopicStore(directory).bytes())
    }
    @Test fun otherDerivedDataParticipatesInBudget() = runBlocking {
        assertFalse(FileTopicStore(temporary.newFolder(), 100, { 100 }).write(TopicSnapshot()))
    }
}
