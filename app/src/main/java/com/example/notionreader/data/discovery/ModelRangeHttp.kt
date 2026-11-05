package com.example.notionreader.data.discovery

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

internal data class ModelRangeResponse(
    val input: InputStream,
    val append: Boolean
)

private const val TIMEOUT_MS = 15_000
private const val MAX_REDIRECTS = 10
private val CONTENT_RANGE_REGEX = """(?i)^bytes\s+(\d+)-(\d+)/(\d+)$""".toRegex()

internal fun openModelRange(
    url: String,
    offset: Long,
    expectedBytes: Long
): ModelRangeResponse {
    var currentUrl = url
    var redirects = 0

    while (true) {
        val connection = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
            }
        }

        val responseCode = try {
            connection.responseCode
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }

        when (responseCode) {
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308 -> {
                if (redirects >= MAX_REDIRECTS) {
                    connection.disconnect()
                    throw IOException("Too many redirects ($redirects) while opening $url")
                }
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect response $responseCode missing Location header")
                }
                currentUrl = try {
                    URI(currentUrl).resolve(location).toString()
                } catch (e: Exception) {
                    URI(currentUrl).resolve(URI.create(location.replace(" ", "%20"))).toString()
                }
                redirects++
                continue
            }
            HttpURLConnection.HTTP_PARTIAL -> {
                val contentRange = connection.getHeaderField("Content-Range")
                if (!isValidContentRange(contentRange, offset, expectedBytes)) {
                    try {
                        connection.inputStream.close()
                    } catch (e: Exception) {
                        // ignore secondary error on cleanup
                    } finally {
                        connection.disconnect()
                    }
                    throw IOException(
                        "Invalid or mismatched Content-Range: '$contentRange' " +
                            "(expected offset $offset, end ${expectedBytes - 1}, total $expectedBytes)"
                    )
                }
                val stream = try {
                    connection.inputStream
                } catch (e: Exception) {
                    connection.disconnect()
                    throw e
                }
                return ModelRangeResponse(
                    input = DisconnectingInputStream(stream, connection),
                    append = true
                )
            }
            HttpURLConnection.HTTP_OK -> {
                val stream = try {
                    connection.inputStream
                } catch (e: Exception) {
                    connection.disconnect()
                    throw e
                }
                return ModelRangeResponse(
                    input = DisconnectingInputStream(stream, connection),
                    append = false
                )
            }
            else -> {
                val message = connection.responseMessage
                try {
                    connection.errorStream?.close()
                } catch (e: Exception) {
                    // ignore secondary error on cleanup
                } finally {
                    connection.disconnect()
                }
                throw IOException("HTTP error $responseCode ${message.orEmpty()} for $url".trim())
            }
        }
    }
}

private fun isValidContentRange(header: String?, offset: Long, expectedBytes: Long): Boolean {
    if (header == null) return false
    val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total = match.groupValues[3].toLongOrNull() ?: return false
    return start == offset && end == (expectedBytes - 1) && total == expectedBytes
}

private class DisconnectingInputStream(
    delegate: InputStream,
    private val connection: HttpURLConnection
) : FilterInputStream(delegate) {
    override fun close() {
        try {
            super.close()
        } finally {
            connection.disconnect()
        }
    }
}
