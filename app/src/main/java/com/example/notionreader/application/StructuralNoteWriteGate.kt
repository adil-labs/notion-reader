package com.example.notionreader.application

import com.example.notionreader.domain.document.*

/** Admission shares the existing disk mutex; recheck combined Note bytes inside that critical section. */
class StructuralNoteWriteGate(
    private val shared: StorageWriteGate,
    private val noteLimit: () -> Long,
    private val noteBytes: () -> Long
) : StorageWriteGate {
    override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T =
        shared.write(category, additionalPeakBytes) {
            val limit = noteLimit().coerceAtLeast(0)
            val used = noteBytes().coerceAtLeast(0)
            if (used > limit || additionalPeakBytes > limit - used) throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
            block()
        }
}
