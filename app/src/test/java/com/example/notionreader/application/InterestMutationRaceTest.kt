package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InterestMutationRaceTest {
    @Test fun removingAnInterestRejectsItsLateMatchingFailure() = runBlocking {
        withFailingMatch { controller, id, release, work ->
            controller.removeInterest(id)
            release.complete(Unit); work.join()
            assertEquals(InterestMatchOutcome.Idle, controller.state.value.interestMatch.outcome)
            assertNull(controller.state.value.interestMatch.interestId)
        }
    }

    @Test fun editingAnInterestRejectsFailureFromItsPreviousDefinition() = runBlocking {
        withFailingMatch { controller, id, release, work ->
            controller.updateFollow(id, "New name", "new definition")
            release.complete(Unit); work.join()
            assertEquals(InterestMatchOutcome.Idle, controller.state.value.interestMatch.outcome)
            assertEquals("new definition", controller.state.value.interests.single().text)
        }
    }

    private suspend fun CoroutineScope.withFailingMatch(
        check: suspend (DiscoveryController, String, CompletableDeferred<Unit>, Job) -> Unit
    ) {
        val directory = Files.createTempDirectory("interest-mutation-race").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : EmbeddingProvider {
            override val identity = EmbeddingIdentity("interest-race", 2)
            override val tokenizer = object : SemanticTokenizer {
                override fun tokens(text: String) = emptyList<SemanticToken>()
            }
            override suspend fun embed(text: String): List<Float> {
                entered.complete(Unit); release.await(); error("Injected late matching failure")
            }
        }
        var work: Job? = null
        try {
            val controller = DiscoveryController(FileDocumentCache(directory.resolve("cache")),
                FileSemanticIndexStore(directory.resolve("index")), FileDiscoveryInterestStore(directory.resolve("interests")), provider)
            controller.saveInterest(null, "forest ecology")
            val id = controller.state.value.interests.single().id
            work = launch { controller.loadInterestMatches(id) }
            withTimeout(5_000) { entered.await() }
            check(controller, id, release, work)
        } finally { release.complete(Unit); work?.cancelAndJoin(); directory.deleteRecursively() }
    }
}
