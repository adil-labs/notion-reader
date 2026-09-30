package com.example.notionreader.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.notionreader.domain.queue.SmartQueue
import com.example.notionreader.domain.queue.SmartQueueEffect
import com.example.notionreader.domain.queue.SmartQueueStore
import kotlinx.coroutines.flow.first

private val Context.smartQueues by preferencesDataStore(name = "smart_queues")

/**
 * Smart Queues on disk backed by DataStore preferences.
 */
class DataStoreSmartQueueStore(private val context: Context) : SmartQueueStore {

    override suspend fun read(): List<SmartQueue> {
        val encoded = try {
            context.smartQueues.data.first()[QUEUES]
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()

        return SmartQueueCodec.decode(encoded)
    }

    override suspend fun transact(
        decide: (List<SmartQueue>) -> Pair<List<SmartQueue>, SmartQueueEffect>
    ): SmartQueueEffect {
        var effect: SmartQueueEffect = SmartQueueEffect.StoreUnavailable

        try {
            context.smartQueues.edit { preferences ->
                val current = preferences[QUEUES]?.let { SmartQueueCodec.decode(it) } ?: emptyList()
                val (next, outcome) = decide(current)
                effect = outcome

                if (next != current) {
                    preferences[QUEUES] = SmartQueueCodec.encode(next)
                }
            }
        } catch (_: Exception) {
            return SmartQueueEffect.StoreUnavailable
        }

        return effect
    }

    override suspend fun clear() {
        try {
            context.smartQueues.edit { preferences ->
                preferences.remove(QUEUES)
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        val QUEUES = stringPreferencesKey("queues")
    }
}
