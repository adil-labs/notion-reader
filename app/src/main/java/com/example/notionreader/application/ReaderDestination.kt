package com.example.notionreader.application

/**
 * A place the user can be, independent of how it is rendered.
 *
 * Split into **top-level** destinations, which the shell's navigation bar offers, and **secondary** ones,
 * which are pushed on top of a top-level destination. The UI contract is explicit that the split is not
 * cosmetic: "The Player is not a fifth bottom-navigation destination" and "The Note screen is not a
 * top-level destination". So the distinction is a property of a destination rather than a fact about
 * which composable happens to render it, and [isTopLevel] is what the shell and the navigator agree on.
 */
enum class ReaderDestination {
    Home,

    /** "useful starting points" — Continue listening, and later Your queues and Explore. */
    Discover,

    /** "the primary semantic discovery interface" — the default result set and search. */
    Browse,

    /** Listening history, and later manually curated and query-based queues. */
    Queues,

    /** Settings hub: playback, speech, offline storage, accessibility, and appearance preferences. */
    Settings,
    Interests,

    RelatedNotes,
    GuidedListening,
    DiscoverySeeds,
    DiscoveryTopics,
    DiscoveryTopic,
    DiscoveryConnection,
    DiscoveryConnections,
    DiscoveryInterest,
    SettingsDiscovery,

    // Settings Subpages (secondary destinations)
    SettingsPlayback,
    SettingsSpeech,
    SettingsOffline,
    SettingsAppearance,
    SettingsNotion,
    SettingsPrivacy,
    SettingsAbout,

    /**
     * One Saved Queue: its members, `Play`, and later the controls that edit it.
     */
    SavedQueueDetails,

    /**
     * One Smart Queue: its query, preview of matching notes, and Play/Rename/Delete.
     */
    SmartQueueDetails,

    /**
     * Choose which Saved Queue receives the Notes `Add to queue` or `Add all to queue` captured.
     */
    QueueDestination,

    /**
     * Name a new Saved Queue. Reached from the Saved tab's `Create saved queue`.
     */
    CreateSavedQueue,

    /**
     * Name and query for a new Smart Queue. Reached from the Smart tab's `Create smart queue`.
     */
    CreateSmartQueue,

    /** Complete Area and Topic discovery taxonomy, reached from Home's Explore preview. */
    ExploreCatalog,

    /** Manual reading and inspection. Reached from a Browse result, and later from a queue row. */
    Note,

    /** Continuous spoken reading. Reached from Play, from Play all, and from the mini-player. */
    Player;

    val isTopLevel: Boolean get() = this == Home || this == Discover || this == Browse || this == Queues || this == Settings
}
