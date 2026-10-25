package com.example.notionreader.data.discovery

import androidx.annotation.Keep
import com.example.notionreader.domain.discovery.SemanticToken
import com.example.notionreader.domain.discovery.SemanticTokenizer
import java.io.File

/** Uses the exact SentencePiece section in the verified bundle; offsets are UTF-16. */
class GemmaTokenizer(private val file: File) : SemanticTokenizer, AutoCloseable {
    private var handle = 0L
    private fun activeHandle(): Long {
        if (handle == 0L) handle = NativeTokenizerBridge.open(file.absolutePath,
            GemmaModelPack.tokenizerOffset, GemmaModelPack.tokenizerBytes).also { check(it != 0L) }
        return handle
    }
    @Synchronized override fun tokens(text: String): List<SemanticToken> {
        val triples = NativeTokenizerBridge.encode(activeHandle(), text, false)
        check(triples.size % 3 == 0)
        return (triples.indices step 3).map { i -> SemanticToken(triples[i], triples[i + 1], triples[i + 2]) }
    }
    @Synchronized fun inputIds(text: String): LongArray {
        val triples = NativeTokenizerBridge.encode(activeHandle(), GemmaModelPack.instructedText(text), true)
        check(triples.size % 3 == 0)
        return LongArray(triples.size / 3) { triples[it * 3].toLong() }
    }
    @Synchronized override fun close() {
        if (handle != 0L) { NativeTokenizerBridge.close(handle); handle = 0 }
    }
}

@Keep
internal object NativeTokenizerBridge {
    init {
        val hostLibrary = System.getProperty("discovery.tokenizer.library")
        if (hostLibrary != null) System.load(hostLibrary) else System.loadLibrary("discovery_tokenizer")
    }
    external fun open(path: String, offset: Long, bytes: Int): Long
    external fun encode(handle: Long, text: String, special: Boolean): IntArray
    external fun close(handle: Long)
}
