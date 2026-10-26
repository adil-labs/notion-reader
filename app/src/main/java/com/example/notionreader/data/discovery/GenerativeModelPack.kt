package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.TopicLabelIdentity
import com.example.notionreader.domain.discovery.TopicLabelEvidencePolicy
import com.example.notionreader.domain.discovery.semanticHash

/** Verified published pack; generation identity never substitutes for the embedding identity. */
object GenerativeModelPack {
    // Enabled by explicit user instruction; outstanding acceptance measurements remain recorded in decisions.
    const val enabledForDiscovery = true
    const val pendingAcceptanceMessage = "Topic naming is under validation; generation is not enabled."
    const val repository = "litert-community/gemma-4-E2B-it-litert-lm"
    const val revision = "1caa527c1e88b55f046fcd8d2b4265606680663d"
    const val bundleFile = "gemma-4-E2B-it.litertlm"
    val artifact = ModelArtifact(bundleFile, 2588147712L,
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
    const val tokenizerOffset = 32768L
    const val tokenizerBytes = 4689013
    const val tokenizerSha256 = "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03"
    const val metadataSha256 = "00603838ac80fadd374ff3d7b6108cfa765bf1a76adfb9df95b3387d0d7e386f"
    const val contextTokens = 2048
    const val outputTokens = 512
    const val description = "Gemma 4 E2B · 2.41 GiB download · local topic labeling · Apache-2.0"
    internal const val schema = """{"type":"object","additionalProperties":false,"required":["topics"],"properties":{"topics":{"type":"array","minItems":1,"maxItems":1,"items":{"type":"object","additionalProperties":false,"required":["label","definition","passage_ids"],"properties":{"label":{"type":"string","minLength":1,"maxLength":96},"definition":{"type":"string","minLength":1,"maxLength":512},"passage_ids":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string"}}}}}}}"""
    fun identity(backend: String) = TopicLabelIdentity(artifact.sha256, tokenizerSha256,
        "bundled-metadata:$metadataSha256", semanticHash(TopicLabelInstruction), semanticHash(schema),
        "concept-v1-wire1-c$contextTokens-o$outputTokens-bundle${TopicLabelEvidencePolicy.version}", backend)
}
