package com.example.notionreader

/**
 * Inspection port for network meter status, used to govern Wi-Fi-only background prefetching.
 */
interface NetworkState {
    /** True when the active network connection is unmetered (such as Wi-Fi or unmetered ethernet). */
    val isUnmetered: Boolean
}

/**
 * Default unmetered implementation for tests and JVM environments.
 */
object UnmeteredNetworkState : NetworkState {
    override val isUnmetered: Boolean = true
}
