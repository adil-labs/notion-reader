package com.example.notionreader.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.notionreader.reader.SessionCodec
import com.example.notionreader.reader.SessionStore
import com.example.notionreader.reader.StoredSession
import kotlinx.coroutines.flow.first

/**
 * The property backing the history file.
 *
 * A `preferencesDataStore` delegate rather than a hand-made `DataStore<Preferences>` field, because the
 * delegate is what guarantees one instance per file per process. Two `DataStore` instances over the same
 * file is a documented way to corrupt it, and the way to get two is to construct this class twice — which
 * an Activity recreation on a configuration change would do.
 */
private val Context.sessionHistory by preferencesDataStore(name = "listening_history")

/**
 * Listening history on disk.
 *
 * `datastore-preferences` was declared in `app/build.gradle.kts` and referenced nowhere for several
 * rounds: it was a vestige of the persistence work that the integration spec's `C1` moved out of that
 * spec's ownership and into the Active Queue & Reader Session spec, which did not exist. This class is
 * where it finally earns its place, which is what `docs/decisions.md` §12 recorded it as waiting for.
 *
 * ## Preferences rather than a typed DataStore
 *
 * The record's shape is already versioned and already serialized by [SessionCodec], so a typed
 * `DataStore<SessionHistory>` would add a second schema — the Preferences wrapper's — over a schema that
 * is deliberately explicit. One string holding the encoded records is the honest storage model for a
 * versioned blob, and it keeps the versioning in exactly one place.
 *
 * ## Failure is swallowed on purpose
 *
 * The spec requires that a read failure "is reported as an empty history" and that a failed write must not
 * affect playback. Both are handled here rather than at the call sites, so that no caller can accidentally
 * turn a storage problem into something the listener sees. This is a boundary against untrusted bytes and
 * an unreliable filesystem, not a place where a programming error should be hidden — so the catch is
 * narrow: an [Exception] from DataStore is a storage condition, and anything outside that is not caught.
 */
class DataStoreSessionStore(private val context: Context) : SessionStore {

    override suspend fun read(): List<StoredSession> {
        val encoded = try {
            context.sessionHistory.data.first()[HISTORY]
        } catch (failure: Exception) {
            null
        }

        return SessionCodec.decode(encoded.orEmpty())
    }

    override suspend fun write(sessions: List<StoredSession>) {
        try {
            context.sessionHistory.edit { preferences ->
                preferences[HISTORY] = SessionCodec.encode(sessions)
            }
        } catch (failure: Exception) {
            // A lost history entry, deliberately not a playback failure.
        }
    }

    private companion object {
        val HISTORY = stringPreferencesKey("sessions")
    }
}
