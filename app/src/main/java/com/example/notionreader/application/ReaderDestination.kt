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
 * ## Why Browse and Home are here, and Settings and Queues are not
 *
 * The contract names four top-level destinations (Home, Browse, Queues, Settings). Two ship, and the
 * reason is the same in each case: a top-level destination with nothing in it is an empty screen, which
 * is what the product decision in `docs/decisions.md` §12.5 rejected for Settings. Queues has no content
 * yet (Listening needs persistence; Saved and Smart need specifications that do not exist) and Settings
 * has none by definition.
 *
 * Browse ships because its *default state* is implementable without the missing Content Metadata spec
 * (§14.1), and Home ships because the shell needs a second populated destination to exist at all, plus
 * Continue listening, which is a projection of the session that already does.
 *
 * This is a scoped deferral rather than a redefinition: invariant 1 still names four, and adding Queues
 * or Settings later is an enum member and a tab.
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

    /** Manual reading and inspection. Reached from a Browse result, and later from a queue row. */
    Note,

    /** Continuous spoken reading. Reached from Play, from Play all, and from the mini-player. */
    Player;

    val isTopLevel: Boolean get() = this == Home || this == Browse
}
