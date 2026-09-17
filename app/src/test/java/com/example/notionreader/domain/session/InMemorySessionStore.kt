package com.example.notionreader.domain.session

/**
 * A [SessionStore] that keeps history in memory, and can be made to fail.
 *
 * Deliberately not a trivial stub. Two of this project's real bugs were found by test doubles that could
 * fail the way the real dependency fails — `FakeReaderEngine` mirrors the real engine's destructive
 * `setDocument` for exactly that reason — so this one can be told to fail reads and writes, because "a
 * failed write must not affect playback" and "a failed read degrades to an empty history" are
 * requirements, and a double that cannot fail cannot test them.
 *
 * It also counts, so "nothing read the store" and "nothing wrote it" are assertable rather than inferred.
 * That matters for the rule that persistence is never on the playback path: the cheap way to satisfy it is
 * not to write while playing, and the way to prove that is to look.
 */
class InMemorySessionStore(
    initial: List<StoredSession> = emptyList()
) : SessionStore {

    var stored: List<StoredSession> = initial
        private set

    /** When true, [read] reports an unreadable store, which is what the spec says must be harmless. */
    var failReads: Boolean = false

    /** When true, [write] silently does nothing, which is what the spec says must be harmless. */
    var failWrites: Boolean = false

    var readCount: Int = 0
        private set
    var writeCount: Int = 0
        private set

    /** Every list passed to [write], in order, so a test can see what was written when. */
    val writes: MutableList<List<StoredSession>> = mutableListOf()

    override suspend fun read(): List<StoredSession> {
        readCount++
        return if (failReads) emptyList() else stored
    }

    override suspend fun write(sessions: List<StoredSession>) {
        writeCount++
        if (failWrites) return
        writes += sessions
        stored = sessions
    }
}
