package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.EmbeddingIdentity
import com.example.notionreader.domain.discovery.TopicPolicy

/** Published LiteRT bundle; no local graph conversion or weight export. */
object GemmaModelPack {
    const val revision = "9be6e8b90982095dc05c2bd162e4b954ee4dbac7"
    const val repository = "litert-community/embeddinggemma-2-text-270m-litert-lm"
    const val instruction = "task: clustering | text: "
    const val bundleFile = "embeddinggemma-2-text-270m.litertlm"
    const val npuBundleFile = "embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm"
    val npuArtifacts = listOf(ModelArtifact(npuBundleFile, 187596800,
        "b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99"))
    fun instructedText(text: String): String = instruction + text.trim()
    // Section location is verified by inspect-litert-tokenizer.py against this bundle's SHA256.
    const val tokenizerOffset = 32768L
    const val tokenizerBytes = 4689013
    const val tokenizerSha256 = "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03"
    val artifacts = listOf(
        ModelArtifact(bundleFile, 164626432, "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb")
    )
    val identity = EmbeddingIdentity(
        "embeddinggemma-2:litert:int4:$revision:${artifacts[0].sha256}", 768,
        tokenizer = "bundled-sentencepiece:$tokenizerSha256", preprocessing = 4, chunker = 2
    )
    val description = "EmbeddingGemma 2 INT4 · 165 MB download · text only · Apache-2.0 · published LiteRT-LM bundle · local inference"
    // Initial shipping-engine calibration: WOS publisher-category proxy, not human acceptance.
    val topicPolicy = TopicPolicy(retention = .89f, version = 16, connection = .90f,
        formationDistance = .10f, membership = .90f)
    const val recommendationThreshold = .90f
}
