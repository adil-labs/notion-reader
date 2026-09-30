package com.example.notionreader.data.notion

import com.example.notionreader.BuildConfig

/**
 * Port for supplying Notion authentication credentials.
 *
 * Decouples the Notion API transport from compile-time [BuildConfig] constants, allowing
 * release implementations to source tokens from OAuth sessions, EncryptedSharedPreferences,
 * or an Android Keystore-backed authenticator without modifying repositories or view models.
 */
fun interface NotionTokenProvider {
    suspend fun getToken(): String?
}

/**
 * Default implementation backed by compile-time [BuildConfig.NOTION_TOKEN].
 * Suitable for local development and POC validation.
 */
class BuildConfigTokenProvider(
    private val token: String = BuildConfig.NOTION_TOKEN
) : NotionTokenProvider {
    override suspend fun getToken(): String? = token.takeIf { it.isNotBlank() }
}
