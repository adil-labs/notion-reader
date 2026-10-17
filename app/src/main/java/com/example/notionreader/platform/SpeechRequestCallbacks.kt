package com.example.notionreader.platform

/** Single active native request; called only on the adapter's main thread. */
internal class SpeechRequestCallbacks(private val onFailure: () -> Unit) {
    private var currentId: String? = null
    private var completion: (() -> Unit)? = null

    fun submit(id: String, onComplete: (() -> Unit)?) {
        currentId = id
        completion = onComplete
    }

    fun enqueue(id: String, onComplete: (() -> Unit)?, action: () -> Boolean) {
        submit(id, onComplete)
        try {
            if (!action()) fail(id)
        } catch (_: Exception) { fail(id) }
    }

    fun complete(id: String?): Boolean {
        if (id == null || id != currentId) return false
        val callback = completion
        cancel()
        callback?.invoke()
        return true
    }

    fun fail(id: String?) {
        if (id == null || id != currentId) return
        cancel()
        onFailure()
    }

    fun cancel() { currentId = null; completion = null }
}
