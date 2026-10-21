package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelArtifact(val path: String, val bytes: Long, val sha256: String,
    val remotePath: String = path)

/** Optional download, never a constructor side effect. Only a verified complete pack is usable. */
class LocalDiscoveryModelManager(
    private val directory: File,
    private val scope: CoroutineScope,
    private val unmetered: () -> Boolean,
    private val onInstalled: suspend () -> Unit = {},
    private val onDeleted: suspend () -> Unit = {},
    private val artifacts: List<ModelArtifact> = Artifacts,
    private val open: (String) -> InputStream = ::openModelUrl,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val repository: String = "sentence-transformers/all-MiniLM-L6-v2",
    private val revision: String = Revision,
    private val description: String = "English MiniLM-L6-v2 · ${artifacts.sumOf { it.bytes }} bytes · sentence-transformers/all-MiniLM-L6-v2 · Apache-2.0 · ONNX Runtime MIT · local inference",
    private val writeGate: com.example.notionreader.domain.document.StorageWriteGate = com.example.notionreader.domain.document.StorageWriteGate.Unrestricted
) : DiscoveryModelManager {
    companion object {
        const val Revision = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
        val Artifacts = listOf(
            ModelArtifact("model_qint8_arm64.onnx", 23026053, "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474", "onnx/model_qint8_arm64.onnx"),
            ModelArtifact("vocab.txt", 231508, "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3")
        )
        private fun openModelUrl(url: String): InputStream {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            val input = connection.inputStream
            return object : java.io.FilterInputStream(input) {
                override fun close() { try { super.close() } finally { connection.disconnect() } }
            }
        }
    }
    val modelDirectory: File get() = File(directory, "current")
    private val mutex = Mutex()
    private var job: Job? = null
    private val mutable = MutableStateFlow(DiscoveryModelState(
        totalBytes = artifacts.sumOf { it.bytes },
        description = description
    ))
    override val state = mutable.asStateFlow()

    /** Called from the process worker, not the UI thread; integrity is checked after process death. */
    suspend fun checkInstalled() = withContext(ioDispatcher) { mutex.withLock {
        val previous = File(directory, "previous")
        if (!modelDirectory.exists() && previous.exists()) check(previous.renameTo(modelDirectory))
        val valid = File(modelDirectory, "installed").let { it.isFile && it.readText() == revision } && artifacts.all { artifact ->
            val file = File(modelDirectory, artifact.path)
            file.isFile && file.length() == artifact.bytes && digest(file) == artifact.sha256
        }
        mutable.update { it.copy(installed = valid) }
        if (valid && previous.exists()) check(previous.deleteRecursively())
        // No download can own staging while startup validation owns this mutex.
        File(directory, "staging").deleteRecursively()
    } }

    override fun setWifiOnly(wifiOnly: Boolean) { mutable.update { it.copy(wifiOnly = wifiOnly) } }
    override fun download() {
        if (job?.isActive == true || state.value.installed) return
        job = scope.launch(ioDispatcher) {
            mutex.withLock {
                // Startup integrity checking may finish while this request waits for the lock.
                if (state.value.installed) return@withLock
                if (state.value.wifiOnly && !unmetered()) { mutable.update { it.copy(message = "Connect to an unmetered network or allow mobile data for this download.") }; return@withLock }
                mutable.update { it.copy(downloading = true, message = null, downloadedBytes = 0) }
                val staging = File(directory, "staging")
                try {
                    staging.deleteRecursively(); check(staging.mkdirs())
                    writeGate.write(com.example.notionreader.domain.document.StorageCategory.MODEL, artifacts.sumOf { it.bytes } + 4096) { }
                    var downloaded = 0L
                    for (artifact in artifacts) {
                        require(artifact.path == File(artifact.path).name)
                        require(artifact.remotePath.split('/').none { it == ".." || it.isEmpty() })
                        val url = "https://huggingface.co/$repository/resolve/$revision/${artifact.remotePath}"
                        val target = File(staging, artifact.path)
                        open(url).use { input -> FileOutputStream(target).use { output ->
                            val buffer = ByteArray(1024*1024)
                            var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                check(!state.value.wifiOnly || unmetered()) { "Network policy changed" }
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read; require(count <= artifact.bytes) { "Unexpected artifact size" }
                                writeGate.write(com.example.notionreader.domain.document.StorageCategory.MODEL, read.toLong() + 4096) {
                                    output.write(buffer, 0, read)
                                }
                                downloaded += read
                                mutable.update { it.copy(downloadedBytes = downloaded) }
                            }
                            output.fd.sync()
                        } }
                        require(target.length() == artifact.bytes && digest(target) == artifact.sha256) { "Model integrity check failed" }
                    }
                    currentCoroutineContext().ensureActive()
                    File(staging, "installed").writeText(revision)
                    val previous = File(directory, "previous")
                    check(!previous.exists() || previous.deleteRecursively())
                    if (modelDirectory.exists()) check(modelDirectory.renameTo(previous))
                    try {
                        try { Files.move(staging.toPath(), modelDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging.toPath(), modelDirectory.toPath()) }
                    } catch (failure: Exception) {
                        if (!modelDirectory.exists() && previous.exists()) check(previous.renameTo(modelDirectory))
                        throw failure
                    }
                    previous.deleteRecursively()
                    mutable.update { it.copy(installed = true, downloading = false) }
                    onInstalled()
                } catch (cancelled: CancellationException) {
                    mutable.update { it.copy(message = "Model download cancelled.") }; throw cancelled
                } catch (_: com.example.notionreader.domain.document.StorageRefused) {
                    mutable.update { it.copy(message = "Not enough safe device storage for the model download. Free space and retry.") }
                } catch (_: Exception) { mutable.update { it.copy(installed = false, message = "Model download failed or could not be verified. Retry the download.") } }
                finally { staging.deleteRecursively(); mutable.update { it.copy(downloading = false) } }
            }
        }
    }
    override fun cancelDownload() { job?.cancel() }
    override suspend fun delete() {
        job?.cancelAndJoin()
        withContext(ioDispatcher) { mutex.withLock {
            mutable.update { it.copy(installed = false, downloading = false) }
            onDeleted()
            check(!modelDirectory.exists() || modelDirectory.deleteRecursively())
            File(directory, "staging").deleteRecursively()
            File(directory, "previous").deleteRecursively()
            mutable.update { it.copy(downloadedBytes = 0, message = null) }
        } }
    }
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
