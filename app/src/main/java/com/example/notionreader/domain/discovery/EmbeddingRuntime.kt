package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.settings.DiscoveryBackend
import kotlinx.coroutines.flow.StateFlow

data class EmbeddingRuntimeState(
    val requested: DiscoveryBackend = DiscoveryBackend.AUTOMATIC,
    val effective: DiscoveryBackend? = null,
    val reason: String? = null,
    val benchmarkNanos: Map<DiscoveryBackend, Long> = emptyMap()
)

interface ObservableEmbeddingRuntime {
    val runtimeState: StateFlow<EmbeddingRuntimeState>
}
