package com.example.notionreader.application

import com.example.notionreader.domain.document.*
import com.example.notionreader.domain.settings.ReaderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All callbacks run off the main thread. Disk bytes include active staging. */
class StorageBudgetCoordinator(
    private val settings: () -> ReaderSettings,
    private val snapshot: () -> StorageSnapshot?,
    private val managedBytes: () -> Long,
    private val discoveryBytes: () -> Long,
    private val modelBytes: () -> Long = { 0L }
) : StorageWriteGate {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(StorageStatus())
    val status = mutable.asStateFlow()

    private fun capacity(): Pair<Long, Long>? {
        val disk = snapshot() ?: return null
        if (disk.totalBytes <= 0 || disk.freeBytes < 0 || disk.freeBytes > disk.totalBytes) return null
        val reserve = maxOf(2L*1024*1024*1024, disk.totalBytes / 10)
        val available = (disk.freeBytes - reserve).coerceAtLeast(0)
        val managed = managedBytes().coerceAtLeast(0)
        val envelope = if (Long.MAX_VALUE - managed < available) Long.MAX_VALUE else managed + available
        val ceiling = envelope / 5 * 4 // overflow-safe eighty percent
        return available to ceiling
    }

    fun refresh() {
        val space = capacity()
        mutable.value = StorageStatus(if (space == null) "Storage availability could not be checked. New downloads and indexing are waiting."
            else if (space.first == 0L) "Device storage is low. New downloads and indexing are waiting; existing offline Notes remain available."
            else null, space?.second ?: 0L, discoveryBytes(), modelBytes())
    }

    override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T = mutex.withLock {
        require(additionalPeakBytes >= 0)
        val space = capacity()
        val prefs = settings()
        val used = discoveryBytes().coerceAtLeast(0)
        val failure = when {
            space == null -> StorageFailure.UNKNOWN_SPACE
            additionalPeakBytes > space.first -> StorageFailure.DEVICE_SPACE
            category == StorageCategory.DISCOVERY && !prefs.discoveryStorageAutomatic &&
                (used > prefs.discoveryCustomLimitBytes || additionalPeakBytes > prefs.discoveryCustomLimitBytes - used) -> StorageFailure.CATEGORY_LIMIT
            prefs.extendedStorage && category != StorageCategory.MODEL &&
                (managedBytes() > space.second || additionalPeakBytes > space.second - managedBytes()) -> StorageFailure.CATEGORY_LIMIT
            else -> null
        }
        if (failure != null) {
            mutable.value = StorageStatus(when (failure) {
                StorageFailure.UNKNOWN_SPACE -> "Storage availability could not be checked. New downloads and indexing are waiting."
                StorageFailure.DEVICE_SPACE -> "Device storage is low. New downloads and indexing are waiting; existing offline Notes remain available."
                StorageFailure.CATEGORY_LIMIT -> "The selected storage limit has been reached. Increase the limit or free storage to continue."
            }, space?.second ?: 0L, discoveryBytes(), modelBytes())
            throw StorageRefused(failure)
        }
        val result = block()
        mutable.value = StorageStatus(extendedMaximumBytes = space!!.second, discoveryBytes = discoveryBytes(), modelBytes = modelBytes())
        result
    }
}
