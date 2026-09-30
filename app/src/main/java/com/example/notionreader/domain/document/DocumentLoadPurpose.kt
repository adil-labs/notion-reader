package com.example.notionreader.domain.document

/**
 * Declares the purpose and urgency of a document load request.
 *
 * Used by load coordination to prioritize foreground interactive use and live
 * playback over speculative background prefetching.
 */
enum class DocumentLoadPurpose {
    /**
     * Active queue playback demands this document immediately to speak to the listener.
     * Highest priority; preempts any unrelated background prefetch I/O.
     */
    PLAYBACK,

    /**
     * User explicitly opened a Note or pressed Refresh.
     * Preempts unrelated background prefetching.
     */
    INTERACTIVE,

    /**
     * Opportunistic background caching (e.g. upcoming queue notes or auto-cache).
     * Expendable; yields immediately to foreground requests.
     */
    PREFETCH
}
