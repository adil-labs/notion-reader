package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.DiscoveryModelManager
import com.example.notionreader.domain.document.StorageWriteGate
import java.io.File
import kotlinx.coroutines.CoroutineScope

/** Separate directory/accounting; reuses the existing atomic verified acquisition protocol. */
class GenerativeModelManager(
    modelsDirectory: File,
    scope: CoroutineScope,
    unmetered: () -> Boolean,
    onInstalled: suspend () -> Unit = {},
    onDeleted: suspend () -> Unit = {},
    writeGate: StorageWriteGate = StorageWriteGate.Unrestricted
) : DiscoveryModelManager {
    private val delegate = LocalDiscoveryModelManager(File(modelsDirectory, "generative"), scope, unmetered,
        onInstalled = onInstalled, onDeleted = onDeleted, artifacts = listOf(GenerativeModelPack.artifact),
        repository = GenerativeModelPack.repository, revision = GenerativeModelPack.revision,
        description = GenerativeModelPack.description, writeGate = writeGate)
    override val state = delegate.state
    val modelDirectory: File get() = delegate.modelDirectory
    suspend fun checkInstalled() = delegate.checkInstalled()
    override fun download() = delegate.download()
    override fun cancelDownload() = delegate.cancelDownload()
    override fun setWifiOnly(wifiOnly: Boolean) = delegate.setWifiOnly(wifiOnly)
    override suspend fun delete() = delegate.delete()
    internal fun verifiedBundle(): File? = File(modelDirectory, GenerativeModelPack.bundleFile)
        .takeIf { state.value.installed && it.isFile && it.length() == GenerativeModelPack.artifact.bytes }
}
