package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.EmbeddingIdentity
import com.example.notionreader.domain.discovery.TopicPolicy

/** One immutable text-only export. External weights must retain their graph-relative filename. */
object GemmaModelPack {
    const val revision = "daa72c51243991dfcaf9f9137d2c573d8f7790c0"
    const val repository = "onnx-community/embeddinggemma-2-ONNX"
    const val instruction = "task: clustering | query: "
    const val graph = "model_q4.onnx"
    val artifacts = listOf(
        ModelArtifact(graph, 490742, "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9", "onnx/$graph"),
        ModelArtifact("model_q4.onnx_data", 174028800, "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49", "onnx/model_q4.onnx_data"),
        ModelArtifact("tokenizer.json", 32170510, "4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4"),
        ModelArtifact("tokenizer_config.json", 1599, "17bd5d6e9364ca49a534e1502076593317c298d4a663623091ed45388f004874"),
        ModelArtifact("config.json", 5031, "8d011bfe08b5e345bbe0b81e5c6fd02c381920b345b986047bc2a33ce7b90d1d")
    )
    val identity = EmbeddingIdentity(
        "embeddinggemma-2:int4:$revision:${artifacts[0].sha256}:${artifacts[1].sha256}", 768,
        tokenizer = "hf-tokenizers-0.22.1:${artifacts[2].sha256}", preprocessing = 2, chunker = 2
    )
    val description = "EmbeddingGemma 2 INT4 · 207 MB download · text only · Gemma terms · community ONNX export · ONNX Runtime MIT · local inference"
    // Initial shipping-engine calibration: WOS publisher-category proxy, not human acceptance.
    val topicPolicy = TopicPolicy(retention = .89f, version = 16, connection = .90f,
        formationDistance = .10f, membership = .90f)
    const val recommendationThreshold = .90f
}
