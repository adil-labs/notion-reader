package com.example.notionreader.data.discovery

/** Immutable published file identity shared by model acquisition and public replay tools. */
data class ModelArtifact(val path: String, val bytes: Long, val sha256: String,
    val remotePath: String = path)
