package com.example.notionreader.platform

import android.os.StatFs
import java.io.File

class AndroidStorageAvailability(private val directory: File) {
    fun snapshot(): Pair<Long, Long>? = try {
        val stat = StatFs(directory.absolutePath)
        stat.totalBytes to stat.availableBytes
    } catch (_: IllegalArgumentException) { null }
}
