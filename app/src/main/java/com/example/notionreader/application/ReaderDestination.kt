package com.example.notionreader.application

/**
 * A place the user can be, independent of how it is rendered.
 *
 * Split into **top-level** destinations, which the shell's navigation bar offers, and **secondary** ones,
 * which are pushed on top of a top-level destination. The UI contract is explicit that the split is not
 * cosmetic: "The Player is not a fifth bottom-navigation destination" and "The Note screen is not a
 * top-level destination". So the distinction is a property of a destination rather than a fact about
 * which composable happens to render it, and [isTopLevel] is what the shell and the navigator agree on.
 *
 * ## Why Browse, Home and Queues are here, and Settings is not
 *
 * The contract names four top-level destinations (Home, Browse, Queues, Settings). Three ship, and the
 * reason is the same in each case: a top-level destination with nothing in it is an empty screen, which
 * is what the product decision in `docs/decisions.md` §12.5 rejected for Settings. Settings has no
 * content by definition.
 *
 * Browse ships because its *default state* is implementable without the missing Content Metadata spec
 * (§14.1). Home ships because the shell needs a second populated destination to exist at all, plus
 * Continue listening, which is a projection of the session that already does. **Queues ships now**
 * because the Active Queue & Reader Session spec made listening history real, and history had no surface:
 * `ReaderApp.completedSessions` could list what the listener had finished and nothing could show it.
 *
 * Queues ships with **one of its three tabs**, which is a scoped deferral of the same kind as the
 * destination itself. The UI contract's Queues screen is `Listening | Saved | Smart`; Saved and Smart are
 * persistent queue storage, and the two specifications that would define them do not exist - the
 * integration spec explicitly delegates Saved Queue and Smart Queue persistence elsewhere. Shipping them
 * empty would put two permanently unusable controls one tap from a populated surface, which is the
 * objection §12.5 already resolved for a whole destination. The tab structure is in place, so each
 * deferred tab is one tab and one screen.
 *
 * This is a scoped deferral rather than a redefinition: invariant 1 still names four destinations, the
 * contract's tab list is unchanged, and nothing here redefines Saved or Smart Queue semantics.
 *
 * Note that the two destinations that used to be all there was are now both **secondary**: with two
 * screens and no shell, `Note` and `Player` were the whole graph, and now they are the two pushed
 * screens in it.
 */
enum class ReaderDestination {

    /** "useful starting points" — Continue listening, and later Your queues and Explore. */
    Home,

    /** "the primary semantic discovery interface" — the default result set and search. */
    Browse,

    /** Listening history, and later manually curated and query-based queues. */
    Queues,

    /**
     * One Saved Queue: its members, `Play`, and later the controls that edit it.
     *
     * Secondary rather than top-level, because the contract puts it under Queues' Saved tab and under
     * Home's *Your queues* — it is opened *from* a list rather than being a destination in its own right.
     *
     * It is the **first destination that needs to be told which one it is about**. Every other screen here
     * either reads what is being played (the Player), is a list (Browse, Queues), or is addressed by a
     * page id it was opened for (Note). A queue's details need a queue, and the navigation model had no
     * place to put one; see [ReaderStop.subjectId].
     */
    SavedQueueDetails,

    /**
     * Choose which Saved Queue receives the Notes `Add to queue` or `Add all to queue` captured.
     *
     * Secondary, and reached from two different screens — the Note and Browse — which is why it is a
     * destination rather than a dialog owned by either: the set of queues it offers does not depend on where
     * the Notes came from, and the contract is explicit that it shows the same three things either way.
     *
     * It is **about a pending set of Notes**, and that set lives in `ReaderApp` rather than in this stop's
     * `subjectId`. See `ReaderApp.pendingAdd` for why a payload is not an identity.
     */
    QueueDestination,

    /**
     * Name a new Saved Queue. Reached from the Saved tab's `Create saved queue`.
     *
     * A screen rather than a dialog, because the contract describes it as one and because the state it
     * needs — a name being typed that is not yet valid — has to live somewhere, which is what
     * [com.example.notionreader.domain.queue.SavedQueueDraft] is for.
     */
    CreateSavedQueue,

    /** Manual reading and inspection. Reached from a Browse result, and later from a queue row. */
    Note,

    /** Continuous spoken reading. Reached from Play, from Play all, and from the mini-player. */
    Player;

    val isTopLevel: Boolean get() = this == Home || this == Browse || this == Queues
}
