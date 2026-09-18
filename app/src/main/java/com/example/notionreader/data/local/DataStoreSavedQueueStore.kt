package com.example.notionreader.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.domain.queue.SavedQueueStore
import kotlinx.coroutines.flow.first

/**
 * The property backing the Saved Queue file.
 *
 * **A separate file from listening history**, and a `preferencesDataStore` delegate for the same reason the
 * session store uses one: the delegate guarantees one instance per file per process, and two `DataStore`
 * instances over the same file is a documented way to corrupt it.
 *
 * The file is separate rather than a second key in one file because the two records have independent version
 * policies. Sharing a file would not force them to share a version — the codecs are already separate — but it
 * would mean a rewrite of one rewrites the other, and there is no reason for a Saved Queue edit to touch the
 * listening history's bytes.
 */
private val Context.savedQueues by preferencesDataStore(name = "saved_queues")

/**
 * Saved Queues on disk.
 *
 * Deliberately the same shape as [DataStoreSessionStore], down to the narrow catch and the swallowed
 * failure, because the Queue Creation & Queue Management spec requires that this store reuse the session
 * store's technology and policy rather than introduce a second one. Copying the shape is the requirement, not
 * a convenience: a second implementation that made its own choice about whether a read failure is reportable
 * is exactly the drift that requirement exists to prevent.
 *
 * ## Failure is swallowed on purpose, and narrowly
 *
 * The spec: "no operation in this specification reports a storage failure to the listener as a failure of the
 * operation". So a read that cannot be performed reports no queues and a write that cannot be performed is a
 * lost edit — neither surfaces anywhere. The catch is [Exception] rather than something broader, because this
 * is a boundary against an unreliable filesystem and untrusted bytes, not a place where a programming error
 * should be hidden.
 *
 * ## Whole-list writes
 *
 * A queue operation is defined by its postcondition on the *list* — "Move to top" means "this member is
 * first" — so the resulting list is written whole. That is what makes the operation and its persistence the
 * same thing rather than making an operation's meaning depend on the store's ability to express a partial
 * change.
 */
class DataStoreSavedQueueStore(private val context: Context) : SavedQueueStore {

    override suspend fun read(): List<SavedQueue> {
        val encoded = try {
            context.savedQueues.data.first()[QUEUES]
        } catch (failure: Exception) {
            null
        }

        return SavedQueueCodec.decode(encoded.orEmpty())
    }

    override suspend fun write(queues: List<SavedQueue>) {
        try {
            context.savedQueues.edit { preferences ->
                preferences[QUEUES] = SavedQueueCodec.encode(queues)
            }
        } catch (failure: Exception) {
            // A lost edit, deliberately not a failure the listener is told about.
        }
    }

    private companion object {
        /**
         * `queues` rather than `savedQueues`, because the file is already named for what it holds — and a
         * second key in the same file would be a queue kind this specification deliberately does not define.
         */
        val QUEUES = stringPreferencesKey("queues")
    }
}
