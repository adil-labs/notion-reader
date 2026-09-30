package com.example.notionreader.application

/**
 * A Smart Queue as displayed on the Smart tab or Home.
 */
data class SmartQueueCard(
    val queueId: String,
    val name: String,
    val queryText: String,
    val memberDescription: String,
    val lastEditedAt: Long
)
