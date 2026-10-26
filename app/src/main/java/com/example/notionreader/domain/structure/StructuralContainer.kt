package com.example.notionreader.domain.structure

import java.time.Instant

enum class StructuralContainerKind { DATABASE, DATA_SOURCE }

/** Metadata returned by an explicitly authorized native container lookup. */
data class StructuralContainer(
    val id: String,
    val kind: StructuralContainerKind,
    val title: String,
    val parent: StructuralParent,
    val sourceIdentity: String,
    val sourceRevision: String = "",
    val createdAt: Instant? = null,
    val lastEditedAt: Instant? = null,
    val accessibilityState: PageAccessibilityState = PageAccessibilityState.ACCESSIBLE
)
