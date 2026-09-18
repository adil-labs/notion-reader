package com.example.notionreader.data.local

import com.example.notionreader.domain.queue.SAVED_QUEUE_SCHEMA_VERSION
import com.example.notionreader.domain.queue.SavedQueue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The wire form of Saved Queues.
 *
 * ## Deliberately the same shape as [SessionCodec], including its failure modes
 *
 * The Queue Creation & Queue Management spec requires that the store be reused rather than re-invented, and
 * "reused" means the *policy* as well as the technology. So this follows `SessionCodec` closely — an array of
 * encoded records, decoded **element by element**, with malformed input reported as "nothing here" rather
 * than raised. Copying the reasoning rather than re-deriving it is the point: a second codec that made its
 * own choices about unknown keys or partial corruption is exactly the drift the spec's reuse requirement
 * exists to prevent.
 *
 * The one thing that is *not* shared is the record. The two codecs version independently, so a change to a
 * session record never forces a re-read decision about queues.
 *
 * ## Why each record is decoded on its own
 *
 * The obvious encoding — one JSON array of records decoded in a single `decodeFromString` — makes a
 * *set-wide* failure out of a *record-wide* one: one unrecognised field, one record from a future version,
 * and every queue is unreadable. Since the policy is to discard what cannot be understood rather than to
 * migrate it or to fail, the decoder has to be able to discard **one** record, which means decoding the
 * elements individually. A corrupt store degrades to fewer queues.
 *
 * That is the property the tests assert with a **mixed** list — one bad record among good ones — because a
 * single bad record cannot distinguish per-record decoding from set-wide decoding: both return nothing.
 *
 * ## Why the failure modes are caught rather than propagated
 *
 * Bytes on disk are untrusted: they may be truncated, from a future schema, or from another app that reused
 * the file. None of those is a programming error, so none is an exception the caller should handle.
 * [SerializationException] covers malformed input and schema mismatch; [IllegalArgumentException] covers
 * input that is not structurally an array. Anything else — an `OutOfMemoryError`, say — is genuinely
 * exceptional and is deliberately not caught.
 */
internal object SavedQueueCodec {

    private val json = Json {
        // A record written by a later version may carry fields this one does not know. Dropping them here is
        // what lets `schemaVersion` be the only gate on acceptance, rather than the field list silently
        // being one too.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Explicit rather than reified: the reified conversions are extension functions in another package, and
    // naming the serializers makes both types in play — a record, and the element it sits in — visible at the
    // call site rather than inferred.
    private val queue: KSerializer<SavedQueue> = SavedQueue.serializer()
    private val elementList: KSerializer<List<JsonElement>> = ListSerializer(JsonElement.serializer())

    /**
     * Encodes [queues], **sorted by identity**.
     *
     * The sort is not cosmetic and is the one place this codec deliberately differs from `SessionCodec`.
     * Sessions are stored in recency order because their order is meaningful to retention; queues are a *set*
     * with no meaningful order between them, so encoding them in list order would make the stored bytes
     * depend on whatever order the caller happened to collect them in. That matters because the whole list is
     * rewritten on every edit: without a canonical order, editing one queue could reorder the bytes of every
     * other, and the store would churn for no reason.
     *
     * Sorting by identity rather than by name, because a rename must not change where a record sits in the
     * file — identity is the thing that does not move.
     */
    fun encode(queues: List<SavedQueue>): String =
        json.encodeToString(
            elementList,
            queues.sortedBy { it.id }.map { json.encodeToJsonElement(queue, it) }
        )

    /**
     * Decodes [text], keeping what it understands and dropping what it does not.
     *
     * Records that decode but are not [SavedQueue.isValid] are dropped too — the version check lives on the
     * record so that no reader can forget it, and applying it here means the store never hands a caller a
     * record it cannot use.
     */
    fun decode(text: String): List<SavedQueue> {
        val elements = try {
            json.decodeFromString(elementList, text)
        } catch (failure: SerializationException) {
            return emptyList()
        } catch (failure: IllegalArgumentException) {
            return emptyList()
        }

        return elements
            .mapNotNull { element ->
                try {
                    json.decodeFromJsonElement(queue, element)
                } catch (failure: SerializationException) {
                    null
                } catch (failure: IllegalArgumentException) {
                    null
                }
            }
            // The version policy, applied per record rather than to the set: a record this build does not
            // understand is discarded and the rest survive. `isValid` is the one predicate that decides it.
            .filter { it.isValid }
    }

    /**
     * Whether [text] was written by a version this build can read at all.
     *
     * Exposed because the two constants are separate by design and it is worth being able to ask the question
     * without decoding: a store whose bytes are all from a future version reports nothing rather than
     * misreading them, and a test asserts that the two versions are not accidentally equal.
     */
    fun isCurrentVersion(version: Int): Boolean = version == SAVED_QUEUE_SCHEMA_VERSION
}
