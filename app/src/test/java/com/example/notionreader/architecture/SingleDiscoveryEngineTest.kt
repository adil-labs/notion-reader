package com.example.notionreader.architecture

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The supported runtime graph must not silently regain an old engine or build-dependent policy. */
class SingleDiscoveryEngineTest {
    private val module = File(System.getProperty("user.dir")).let { if (File(it, "src/main").exists()) it else File(it, "app") }
    private fun production() = File(module, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun discoveryHasNoExecutableLegacyBuilderOrSwitchingWrapper() {
        val obsolete = Regex("\\b(?:class|interface)\\s+(?:TopicBuilder|StreamingAnchorEngine|TopicDiscoveryEngine)\\b")
        assertTrue(production().flatMap { file -> obsolete.findAll(file.readText()).map { file.name + ": " + it.value }.toList() }.toString(),
            production().none { obsolete.containsMatchIn(it.readText()) })
    }

    @Test fun debugAndReleaseCannotSelectDifferentDiscoveryAlgorithms() {
        val configuration = File(module, "build.gradle.kts").readText()
        assertFalse(configuration.contains("EXPERIMENTAL_DISCOVERY"))
        assertFalse(configuration.contains("experimentalDiscovery"))
        assertTrue(production().none { it.readText().contains("useExperimentalClustering") })
    }

    @Test fun retiredAnchorSweepFieldsAreNotActiveDomainConfiguration() {
        val model = File(module, "src/main/java/com/example/notionreader/domain/discovery/DiscoveredTopics.kt").readText()
        for (field in listOf("maxAnchors", "candidatePassLimit", "candidateSeeds", "candidateCursor", "groupingPass", "provisionalPasses"))
            assertFalse("Retired field: $field", Regex("\\bval\\s+$field\\b").containsMatchIn(model))
    }

    @Test fun publicationCannotSwitchToAnExperimentalEvidencePolicy() {
        assertTrue(production().none { it.readText().contains("experimentalEvidenceValidated") })
    }
}
