package com.example.notionreader.reader

/**
 * Internal, provider-agnostic representation of a readable document.
 *
 * The reader engine and the UI only ever see this type; Notion JSON (or any future
 * provider) is converted into it by a repository. This keeps navigation logic
 * independent of where the content came from.
 */
data class ReaderDocument(
    val id: String,
    val title: String,
    val paragraphs: List<String>
) {
    companion object {

        val Empty = ReaderDocument(
            id = "",
            title = "",
            paragraphs = emptyList()
        )

        /** Hard-coded document used to prove the reader independently of networking. */
        val Demo = ReaderDocument(
            id = "demo",
            title = "Demo document",
            paragraphs = listOf(
                "Welcome to Notion Reader.",
                "This application is testing sequential document playback.",
                "You can move backward or forward through paragraphs.",
                "Later these paragraphs will come directly from Notion."
            )
        )
    }
}

/** Load state of a document that has to be fetched (for example from the Notion API). */
sealed interface DocumentLoadState {

    data object Loading : DocumentLoadState

    data class Loaded(
        val document: ReaderDocument
    ) : DocumentLoadState

    data class Error(
        val message: String
    ) : DocumentLoadState
}
