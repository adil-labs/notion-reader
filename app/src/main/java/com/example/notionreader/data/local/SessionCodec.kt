package com.example.notionreader.data.local

import com.example.notionreader.domain.session.StoredSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * The wire form of listening history.
 *
 * Kept in the reader package, and kept separate from storage, so that the encoding is testable on the JVM
 * with no device and no DataStore. That matters more than it looks: the stored shape is the one thing in
 * this design that a future version cannot simply refactor, and "does this still decode" is a question
 * worth being able to ask in a unit test.
 *
 * ## Why each record is decoded on its own
 *
 * The obvious encoding — one JSON array of records decoded in a single `decodeFromString` — makes a
 * *set-wide* failure out of a *record-wide* one: one unrecognised origin, one field that a future version
 * renamed, and the whole history is unreadable. Since the spec's policy is to discard the records it
 * cannot understand rather than to migrate them or to fail, the decoder has to be able to discard one
 * record, which means decoding the elements individually.
 *
 * So the encoding is an array of encoded records, and decoding walks it element by element, keeping what
 * it understands and dropping what it does not. A corrupt history degrades to a shorter history.
 *
 * ## Why the failure modes are caught rather than propagated
 *
 * The spec requires that history which cannot be read "is reported as an empty history" and that a
 * listener sees no error state for a feature they did not ask for. Bytes on disk are untrusted input:
 * they may be truncated, from a different app that reused the file, or from a future schema. None of
 * those is a programming error, so none of them is an exception the caller should have to handle.
 * [SerializationException] covers malformed input and schema mismatch; [IllegalArgumentException] covers
 * the cases where the JSON is not even structurally an array. Anything else — an `OutOfMemoryError`, say —
 * is genuinely exceptional and is deliberately not caught.
 */
internal object SessionCodec {

    private val json = Json {
        // A record written by a later version may carry fields this one does not know. Dropping them
        // here is what lets `schemaVersion` be the only gate on acceptance, rather than the field list
        // silently being one too.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Explicit rather than reified: the reified `encodeToJsonElement`/`decodeFromJsonElement` are
    // extension functions in a different package, and naming the serializers makes the two types in play
    // - a record, and the element it sits in - visible at the call site instead of inferred.
    private val session: KSerializer<StoredSession> = StoredSession.serializer()
    private val elementList: KSerializer<List<JsonElement>> = ListSerializer(JsonElement.serializer())

    fun encode(sessions: List<StoredSession>): String =
        json.encodeToString(elementList, sessions.map { json.encodeToJsonElement(session, it) })

    fun decode(text: String): List<StoredSession> {
        val elements = try {
            json.decodeFromString(elementList, text)
        } catch (failure: SerializationException) {
            return emptyList()
        } catch (failure: IllegalArgumentException) {
            return emptyList()
        }

        return elements.mapNotNull { element ->
            try {
                json.decodeFromJsonElement(session, element)
            } catch (failure: SerializationException) {
                null
            } catch (failure: IllegalArgumentException) {
                null
            }
        }
    }
}
