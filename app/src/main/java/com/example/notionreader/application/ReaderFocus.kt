package com.example.notionreader.application

/**
 * Focus identifiers for controls that are not identified by content.
 *
 * Most of what the user can activate already has an identity: a Browse row is identified by its page id,
 * and a queue row by its session id. Those ids are passed to [ReaderNavigator.open] directly, because
 * inventing a parallel naming scheme for them would be a second source of truth for the same fact.
 *
 * Two controls have no such identity, and they are the reason this file exists rather than a `const` in
 * the screen that draws them. A screen may not define the id the navigation layer records — `application`
 * cannot depend on `ui`, which is the architecture rule `LayerRulesTest` enforces — and the two classes
 * that draw the controls live in `ui` while [ReaderApp] is what records their ids. Putting them here is
 * the only placement available to both.
 *
 * They are namespaced by the surface they belong to (`note:`, `browse:`) rather than being bare words, so
 * that a future screen adding its own `play` cannot collide with one of these and silently steal focus.
 * Collisions are otherwise possible in principle: a Browse row's id is a page id, which is a UUID and
 * cannot be one of these.
 */
object ReaderFocus {

    /** The Note screen's Play button — "creates a new Active Queue containing only this Note". */
    const val NotePlay: String = "note:play"

    /**
     * Browse's `Play all`.
     *
     * A set-level action rather than a row, so it has no content identity of its own. It is here because
     * a listener who leaves the Player with Back should land on the control they pressed to get there,
     * and `Play all` is how they got there.
     */
    const val BrowsePlayAll: String = "browse:playAll"
    /**
     * Saved Queue Details' `Play`.
     *
     * A set-level action again — it plays the whole queue, not a member — so it has no content identity.
     * It is distinct from [NotePlay] and [BrowsePlayAll] because the three are controls on three different
     * screens: one id for all three would put focus back on the wrong screen's button whenever a listener
     * returned from the Player by a route other than the one they went in by.
     */
    const val SavedQueuePlay: String = "savedQueue:play"

    /**
     * The Saved tab's `Create saved queue`.
     *
     * The destination it opens is about nothing — there is no queue yet — so this is the only identity the
     * push has, and it is what Back returns focus to. That is the case [ReaderStop.subjectId] documents as
     * the reason a stop needs two ids rather than one.
     */
    const val SavedQueueCreate: String = "savedQueue:create"

    /**
     * The Note screen's `Add to queue`.
     *
     * A control rather than a Note, so it is named rather than identified by the page id it acts on: the
     * destination it opens is reached from this screen and from Browse, and a listener who backs out must
     * return to the button they pressed rather than to a row that happens to share an id.
     */
    const val NoteAddToQueue: String = "note:addToQueue"

    /**
     * Browse's `Add all to queue`.
     *
     * Distinct from [BrowsePlayAll] because they are two separate controls on one screen: a listener who left
     * the Player having pressed one of them must land on the one they pressed, and one shared id would put
     * focus on the other.
     */
    const val BrowseAddAllToQueue: String = "browse:addAllToQueue"

    /** Saved Queue Details' `Rename`. */
    const val SavedQueueRename: String = "savedQueue:rename"

    /** Saved Queue Details' `Delete`. */
    const val SavedQueueDelete: String = "savedQueue:delete"

    /** Home screen's `See all queues`. */
    const val HomeSeeAllQueues: String = "home:seeAllQueues"

    /** The Smart tab's `Create smart queue`. */
    const val SmartQueueCreate: String = "smartQueue:create"

    /** Smart Queue Details' `Play`. */
    const val SmartQueuePlay: String = "smartQueue:play"

    /** Smart Queue Details' `Rename`. */
    const val SmartQueueRename: String = "smartQueue:rename"

    /** Smart Queue Details' `Delete`. */
    const val SmartQueueDelete: String = "smartQueue:delete"
}
