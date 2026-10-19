package com.example.notionreader.tooling

import com.example.notionreader.data.discovery.GemmaModelPack
import com.example.notionreader.data.discovery.GemmaTokenizer
import com.example.notionreader.domain.discovery.semanticHash
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Explicit asset-backed JNI check, separate from ordinary offline unit tests. */
object GemmaTokenizerParity {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Pass verified tokenizer.json and the frozen reference JSON" }
        val file = File(args[0])
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val reference = Json.parseToJsonElement(File(args[1]).readText()).jsonObject
        check(sha == reference.getValue("tokenizerSha256").jsonPrimitive.content)
        check(sha == GemmaModelPack.artifacts.single { it.path == "tokenizer.json" }.sha256)
        GemmaTokenizer(file).use { tokenizer ->
            for (item in reference.getValue("cases").jsonArray) {
                val case = item.jsonObject
                val text = case.getValue("text").jsonPrimitive.content
                val tokens = tokenizer.tokens(text)
                check(tokens.map { it.id } == case.getValue("tokenIds").jsonArray.map { it.jsonPrimitive.int }) { "Token mismatch for ${semanticHash(text)}" }
                check(tokens.map { listOf(it.start, it.end) } == case.getValue("utf16Offsets").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } }) { "Offset mismatch for ${semanticHash(text)}" }
                check(tokenizer.inputIds(text).toList() == case.getValue("instructedTokenIds").jsonArray.map { it.jsonPrimitive.long })
            }
            for (item in reference.getValue("embeddingReferences").jsonArray) {
                val case = item.jsonObject
                check(tokenizer.inputIds(case.getValue("input").jsonPrimitive.content).toList() == case.getValue("tokenIds").jsonArray.map { it.jsonPrimitive.long })
            }
            tokenizer.close()
            check(tokenizer.inputIds("hello").isNotEmpty())
        }
        println("JNI tokenizer parity passed: 12 token/UTF-16 cases, 3 public passages, release/reopen")
    }
}
