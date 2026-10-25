package com.example.notionreader.tooling

import com.example.notionreader.data.discovery.GemmaModelPack
import com.example.notionreader.data.discovery.GemmaTokenizer
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Explicit asset-backed JNI check, separate from ordinary offline unit tests. */
object GemmaTokenizerParity {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Pass the published model bundle and frozen SentencePiece reference" }
        val file = File(args[0])
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val reference = Json.parseToJsonElement(File(args[1]).readText()).jsonObject
        check(sha == reference.getValue("bundleSha256").jsonPrimitive.content)
        check(sha == GemmaModelPack.artifacts.single().sha256)
        GemmaTokenizer(file).use { tokenizer ->
            for (item in reference.getValue("cases").jsonArray) {
                val case = item.jsonObject
                val text = case.getValue("text").jsonPrimitive.content
                val tokens = tokenizer.tokens(text)
                check(tokens.map { it.id } == case.getValue("ids").jsonArray.map { it.jsonPrimitive.int })
                check(tokens.map { listOf(it.start, it.end) } == case.getValue("utf16Offsets").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } })
                check(tokenizer.inputIds(text).toList() == case.getValue("instructedIds").jsonArray.map { it.jsonPrimitive.long })
            }
            for (item in reference.getValue("boundaries").jsonArray) {
                val boundary = item.jsonObject
                check(tokenizer.inputIds(" token".repeat(boundary.getValue("repeats").jsonPrimitive.int)).size == boundary.getValue("tokens").jsonPrimitive.int)
            }
            tokenizer.close()
            check(tokenizer.inputIds("hello").isNotEmpty())
        }
        println("Bundled SentencePiece JNI parity passed: ${reference.getValue("cases").jsonArray.size} token/UTF-16 cases, exact context boundaries, release/reopen")
    }
}
