package com.example.notionreader.domain.document

data class StorageSnapshot(val totalBytes: Long, val freeBytes: Long)
enum class StorageCategory { NOTES, DISCOVERY, MODEL }
enum class StorageFailure { UNKNOWN_SPACE, DEVICE_SPACE, CATEGORY_LIMIT }
class StorageRefused(val reason: StorageFailure) : Exception(reason.name)
data class StorageStatus(val message: String? = null, val extendedMaximumBytes: Long = 0L,
    val discoveryBytes: Long? = null, val modelBytes: Long = 0L)

/** Admission spans the write, so competing stores cannot spend the same space. */
interface StorageWriteGate {
    suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T
    companion object {
        val Unrestricted = object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T = block()
        }
    }
}

object StorageDefaults {
    const val MIB = 1024L * 1024
    const val NOTE_BYTES = 128 * MIB
    const val CUSTOM_DISCOVERY_BYTES = 256 * MIB
    val sizeSteps = listOf(32, 64, 128, 256, 512, 1024, 2048).map { it * MIB }
    fun validLimit(value: Long, fallback: Long): Long = value.takeIf { it >= 32 * MIB } ?: fallback
}
