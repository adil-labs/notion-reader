package com.example.notionreader.application

/** Cached-note Discovery does not depend on completion of remote structural collection. */
internal suspend fun <T> discoveryBeforeStructuralWork(
    discovery: suspend () -> T,
    structural: suspend () -> Unit
): T {
    val result = discovery()
    structural()
    return result
}
