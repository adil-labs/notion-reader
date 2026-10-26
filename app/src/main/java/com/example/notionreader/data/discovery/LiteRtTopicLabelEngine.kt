package com.example.notionreader.data.discovery

import com.google.ai.edge.litertlm.*
import java.io.File

/** One engine, fresh conversation per group; callers close it before an embedding engine starts. */
internal class LiteRtTopicLabelEngine(file: File, cacheDirectory: File, backend: TopicLabelBackend) : NativeTopicLabelEngine {
    private val engine = Engine(EngineConfig(modelPath = file.absolutePath,
        backend = if (backend == TopicLabelBackend.GPU) Backend.GPU() else Backend.CPU(threadCount = 6),
        maxNumTokens = GenerativeModelPack.contextTokens, cacheDir = cacheDirectory.absolutePath))
    private var tokenizer = 0L
    init {
        try {
            tokenizer = NativeTokenizerBridge.open(file.absolutePath,
                GenerativeModelPack.tokenizerOffset, GenerativeModelPack.tokenizerBytes)
            check(tokenizer != 0L)
            engine.initialize()
        } catch (failure: Throwable) {
            if (tokenizer != 0L) { NativeTokenizerBridge.close(tokenizer); tokenizer = 0 }
            if (engine.isInitialized()) engine.close()
            throw failure
        }
    }
    override fun conversation(): NativeTopicLabelSession {
        val conversation = engine.createConversation(ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            automaticToolCalling = false, enableResponseFormat = true))
        return object : NativeTopicLabelSession {
            private var closed = false
            @OptIn(ExperimentalApi::class)
            override fun countPromptTokens(prompt: String): Int {
                // Render with the bundle's actual template. Literal control spellings may overcount;
                // never replace this with character estimates or trim source text to fit.
                // The Gemma 4 template reads messages[0]; an empty preface cannot be rendered.
                // Render the complete first message once, including its bundled BOS/role framing.
                val rendered = conversation.renderMessageIntoString(Message.user(prompt))
                val tokens = NativeTokenizerBridge.encode(tokenizer, rendered, false)
                check(tokens.size % 3 == 0)
                return tokens.size / 3
            }
            override fun start(prompt: String, chunk: (String) -> Unit, done: (Throwable?) -> Unit) {
                conversation.sendMessageAsync(prompt, object : MessageCallback {
                    override fun onMessage(message: Message) = chunk(message.toString())
                    override fun onDone() = done(null)
                    override fun onError(throwable: Throwable) = done(throwable)
                }, maxOutputToken = GenerativeModelPack.outputTokens,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    responseFormat = ResponseFormat.json(GenerativeModelPack.schema))
            }
            @Synchronized override fun cancel() { if (!closed) conversation.cancelProcess() }
            @Synchronized override fun close() { if (!closed) { closed = true; conversation.close() } }
        }
    }
    override fun close() {
        try { if (engine.isInitialized()) engine.close() }
        finally { if (tokenizer != 0L) { NativeTokenizerBridge.close(tokenizer); tokenizer = 0 } }
    }
}

enum class TopicLabelBackend(val provenance: String) { GPU("GPU"), CPU("CPU-6-threads") }
