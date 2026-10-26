package com.example.notionreader.domain.discovery

import kotlinx.coroutines.flow.StateFlow

data class DiscoveryModelState(
    val installed: Boolean = false, val downloading: Boolean = false,
    val wifiOnly: Boolean = true, val message: String? = null,
    val description: String = "Optional local Discovery model",
    val downloadedBytes: Long = 0, val totalBytes: Long = 0,
    val storageBytes: Long? = null
)

interface DiscoveryModelManager {
    val state: StateFlow<DiscoveryModelState>
    fun download()
    fun cancelDownload()
    fun setWifiOnly(wifiOnly: Boolean)
    suspend fun delete()
}
