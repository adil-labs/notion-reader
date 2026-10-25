package com.example.notionreader.ui

import com.example.notionreader.data.discovery.ModelArtifact

/** Historical INT8 controls stay fixed when the shipping quantization changes. */
internal object GemmaInt8BenchmarkFixture {
    const val graph = "model_quantized.onnx"
    val artifacts = listOf(
        ModelArtifact(graph, 495165, "d06edd601f851c633a2519304cbeb8dc6170d7ceb61b436625c17fb9b6e74953"),
        ModelArtifact("model_quantized.onnx_data", 313724928, "278a7ff1248c3618e4bd11a607fc54f7bdc7778854230f3956d3f86bd9db4f3b"),
        ModelArtifact("tokenizer.json", 32170510, "4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4"),
        ModelArtifact("tokenizer_config.json", 1599, "17bd5d6e9364ca49a534e1502076593317c298d4a663623091ed45388f004874"),
        ModelArtifact("config.json", 5031, "8d011bfe08b5e345bbe0b81e5c6fd02c381920b345b986047bc2a33ce7b90d1d")
    )
}
