package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.data.document.DOCUMENT_CACHE_SCHEMA_VERSION
import com.example.notionreader.domain.ChunkMetadata
import com.example.notionreader.domain.ReaderChunk
import com.example.notionreader.domain.ReaderChunkKind
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderSection
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Encodes and decodes [CachedDocument] to and from JSON.
 *
 * Keeps serialization DTOs in the data layer so [ReaderDocument] in domain remains
 * free of serialization dependencies.
 */
internal object ReaderDocumentCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(cachedDocument: CachedDocument): String {
        val dto = CachedDocumentDto(
            schemaVersion = cachedDocument.schemaVersion,
            pageId = cachedDocument.document.pageId,
            title = cachedDocument.document.title,
            lastEditedAtEpochMilli = cachedDocument.document.lastEditedAt?.toEpochMilli(),
            remoteLastEditedAtEpochMilli = cachedDocument.remoteLastEditedAt?.toEpochMilli(),
            cachedAtEpochMilli = cachedDocument.validatedAt.toEpochMilli(),
            fetchedAtEpochMilli = cachedDocument.fetchedAt.toEpochMilli(),
            validatedAtEpochMilli = cachedDocument.validatedAt.toEpochMilli(),
            sections = cachedDocument.document.sections.map { section ->
                ReaderSectionDto(
                    id = section.id,
                    title = section.title,
                    level = section.level,
                    chunks = section.chunks.map { chunk ->
                        ReaderChunkDto(
                            id = chunk.id,
                            sourceBlockId = chunk.sourceBlockId,
                            kind = chunk.kind.name,
                            text = chunk.text,
                            metadata = ChunkMetadataDto(
                                checked = chunk.metadata.checked,
                                language = chunk.metadata.language,
                                linkTargets = chunk.metadata.linkTargets,
                                hasInlineCode = chunk.metadata.hasInlineCode,
                                headingLevel = chunk.metadata.headingLevel
                            )
                        )
                    }
                )
            }
        )
        return json.encodeToString(CachedDocumentDto.serializer(), dto)
    }

    fun decode(rawJson: String): CachedDocument? {
        val dto = try {
            json.decodeFromString(CachedDocumentDto.serializer(), rawJson)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        if (dto.schemaVersion != DOCUMENT_CACHE_SCHEMA_VERSION) return null
        if (dto.pageId.isBlank()) return null

        val document = ReaderDocument(
            pageId = dto.pageId,
            title = dto.title,
            lastEditedAt = dto.lastEditedAtEpochMilli?.let { Instant.ofEpochMilli(it) },
            sections = dto.sections.map { sectionDto ->
                val chunks = sectionDto.chunks.map { chunkDto ->
                    val kind = try {
                        ReaderChunkKind.valueOf(chunkDto.kind)
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                    ReaderChunk(
                        id = chunkDto.id,
                        sourceBlockId = chunkDto.sourceBlockId,
                        kind = kind,
                        text = chunkDto.text,
                        metadata = ChunkMetadata(
                            checked = chunkDto.metadata.checked,
                            language = chunkDto.metadata.language,
                            linkTargets = chunkDto.metadata.linkTargets,
                            hasInlineCode = chunkDto.metadata.hasInlineCode,
                            headingLevel = chunkDto.metadata.headingLevel
                        )
                    )
                }
                ReaderSection(
                    id = sectionDto.id,
                    title = sectionDto.title,
                    level = sectionDto.level,
                    chunks = chunks
                )
            }
        )

        val fetchedAt = dto.fetchedAtEpochMilli?.let { Instant.ofEpochMilli(it) }
            ?: dto.cachedAtEpochMilli?.let { Instant.ofEpochMilli(it) }
            ?: Instant.EPOCH
        val validatedAt = dto.validatedAtEpochMilli?.let { Instant.ofEpochMilli(it) }
            ?: dto.cachedAtEpochMilli?.let { Instant.ofEpochMilli(it) }
            ?: fetchedAt

        return CachedDocument(
            document = document,
            remoteLastEditedAt = dto.remoteLastEditedAtEpochMilli?.let { Instant.ofEpochMilli(it) },
            fetchedAt = fetchedAt,
            validatedAt = validatedAt,
            schemaVersion = dto.schemaVersion
        )
    }

    @Serializable
    private data class CachedDocumentDto(
        val schemaVersion: Int,
        val pageId: String,
        val title: String,
        val lastEditedAtEpochMilli: Long? = null,
        val remoteLastEditedAtEpochMilli: Long? = null,
        val cachedAtEpochMilli: Long? = null,
        val fetchedAtEpochMilli: Long? = null,
        val validatedAtEpochMilli: Long? = null,
        val sections: List<ReaderSectionDto> = emptyList()
    )

    @Serializable
    private data class ReaderSectionDto(
        val id: String,
        val title: String? = null,
        val level: Int,
        val chunks: List<ReaderChunkDto> = emptyList()
    )

    @Serializable
    private data class ReaderChunkDto(
        val id: String,
        val sourceBlockId: String,
        val kind: String,
        val text: String,
        val metadata: ChunkMetadataDto = ChunkMetadataDto()
    )

    @Serializable
    private data class ChunkMetadataDto(
        val checked: Boolean? = null,
        val language: String? = null,
        val linkTargets: List<String> = emptyList(),
        val hasInlineCode: Boolean = false,
        val headingLevel: Int? = null
    )
}
