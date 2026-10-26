package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Strict bounded wire parsing; native grammar success is not source/semantic validation. */
internal fun parseTopicLabelResponse(raw: String, request: TopicLabelRequest): TopicLabelCandidate? = try {
    require(raw.length <= 32768 && raw.toByteArray(Charsets.UTF_8).size <= 32768)
    JsonReader(StringReader(raw)).use { reader ->
        reader.beginObject()
        require(reader.hasNext() && reader.nextName() == "topics")
        reader.beginArray()
        require(reader.hasNext())
        reader.beginObject()
        val keys = mutableSetOf<String>()
        var label: String? = null
        var definition: String? = null
        var ids: List<String>? = null
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(keys.add(key))
            when (key) {
                "label" -> { require(reader.peek() == JsonToken.STRING); label = reader.nextString() }
                "definition" -> { require(reader.peek() == JsonToken.STRING); definition = reader.nextString() }
                "passage_ids" -> {
                    reader.beginArray()
                    val entries = mutableListOf<String>()
                    while (reader.hasNext()) {
                        require(entries.size < 16 && reader.peek() == JsonToken.STRING)
                        entries += reader.nextString()
                    }
                    reader.endArray(); ids = entries
                }
                else -> error("Unknown label field")
            }
        }
        reader.endObject()
        require(!reader.hasNext())
        reader.endArray()
        require(!reader.hasNext())
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        val candidate = TopicLabelCandidate(checkNotNull(label), checkNotNull(ids), checkNotNull(definition))
        require(validateTopicLabel(request, candidate.text, candidate.evidenceIds,
            request.groupFingerprint, request.sourceEpoch, candidate.definition) is TopicLabelValidation.Accepted)
        candidate
    }
} catch (_: Exception) { null }
