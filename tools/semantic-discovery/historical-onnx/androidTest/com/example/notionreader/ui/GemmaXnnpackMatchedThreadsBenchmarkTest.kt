package com.example.notionreader.ui

import org.junit.Test

class GemmaXnnpackMatchedThreadsBenchmarkTest {
    @Test fun xnnpackFallbackIsComparedWithTheSameCpuThreadBudget() {
        GemmaBackendBenchmarkTest().runBenchmark(listOf("XNNPACK_CPU2"), "gemma-xnnpack-matched")
    }
}
