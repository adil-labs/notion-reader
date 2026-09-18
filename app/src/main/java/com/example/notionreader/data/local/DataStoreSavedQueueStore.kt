package com.example.notionreader.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.notionreader.domain.queue.QueueEditOutcome
import com.example.notionreader.domain.queue.QueueEffect
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
 * ## The read and the decision happen inside one `edit` transaction
 *
 * [transact] decodes the stored list, applies the caller's decision and encodes the result **inside a single
 * `DataStore.edit` block**, which is what makes the read and the write one operation. The alternative — read,
 * decide, write — cannot be made safe here: a read failure has to report no queues, so a decision taken on top
 * of that answer would be a decision about an empty library, and writing it back would delete everything the
 * store failed to read. Inside `edit`, a read that throws means the block never runs and nothing is committed.
 *
 * The lambda is synchronous, which `edit` requires and which is available only because the model it calls is
 * pure: `SavedQueueCodec` and `QueueEdit.applyTo` neither suspend nor touch the platform.
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

    override suspend fun transact(decide: (List<SavedQueue>) -> QueueEditOutcome): QueueEditOutcome {
        // Assigned inside the transaction and read after it, because the value to report is the decision the
        // caller's lambda reached — and because a commit that throws must not report that decision as applied.
        var decided: QueueEditOutcome? = null
        try {
            context.savedQueues.edit { preferences ->
                val current = SavedQueueCodec.decode(preferences[QUEUES].orEmpty())
                val next = decide(current)
                // The only write, and it happens only for a decision that says something changed. That is the
                // caller's rule, honoured here rather than re-derived: an operation that changes nothing must
                // not rewrite the file, or a reorder at a boundary would churn it for nothing.
                if (next.changed) preferences[QUEUES] = SavedQueueCodec.encode(next.queues)
                decided = next
            }
        } catch (failure: Exception) {
            // Either the file could not be read, so the transaction never ran, or the commit failed. Neither
            // wrote anything, and neither is a failure of the operation the listener performed.
        }
        return decided ?: UNAVAILABLE
    }

    private companion object {
        /**
         * `queues` rather than `savedQueues`, because the file is already named for what it holds — and a
         * second key in the same file would be a queue kind this specification deliberately does not define.
         */
        val QUEUES = stringPreferencesKey("queues")

        /**
         * The answer for a store that could not be read: nothing happened, so there is nothing to report and
         * no list that can be claimed. Built once here rather than at each site so the three fields cannot
         * drift apart, since the pairing of an unchanged outcome with this effect is the whole statement.
         */
        val UNAVAILABLE = QueueEditOutcome(
            queues = emptyList(),
            changed = false,
            effect = QueueEffect.StoreUnavailable
        )
    }
}
