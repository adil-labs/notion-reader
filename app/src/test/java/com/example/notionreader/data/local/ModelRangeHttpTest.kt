package com.example.notionreader.data.local

import com.example.notionreader.data.discovery.openModelRange
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class ModelRangeHttpTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun endpoint(path: String): String = "http://127.0.0.1:$port$path"

    @Test
    fun valid206RequestReturnsAppendTrueAndExactContent() {
        val capturedRange = AtomicReference<String?>()
        val capturedEncoding = AtomicReference<String?>()
        val payload = ByteArray(50) { (it + 50).toByte() }

        server.createContext("/valid206") { exchange ->
            try {
                capturedRange.set(exchange.requestHeaders.getFirst("Range"))
                capturedEncoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
                exchange.responseHeaders.set("Content-Range", "bytes 50-99/100")
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        val response = openModelRange(endpoint("/valid206"), offset = 50L, expectedBytes = 100L)
        try {
            assertTrue("Expected append=true for 206", response.append)
            assertEquals("bytes=50-", capturedRange.get())
            assertEquals("identity", capturedEncoding.get())
            assertArrayEquals(payload, response.input.readBytes())
        } finally {
            response.input.close()
        }
    }

    @Test
    fun mismatchedStartingOffsetIn206Fails() {
        server.createContext("/mismatched-start") { exchange ->
            try {
                exchange.responseHeaders.set("Content-Range", "bytes 0-99/100")
                val payload = ByteArray(100)
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        try {
            openModelRange(endpoint("/mismatched-start"), offset = 50L, expectedBytes = 100L)
            fail("Expected failure for mismatched starting offset")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Content-Range") == true)
        }
    }

    @Test
    fun mismatchedEndingOffsetIn206Fails() {
        server.createContext("/mismatched-end") { exchange ->
            try {
                exchange.responseHeaders.set("Content-Range", "bytes 50-98/100")
                val payload = ByteArray(49)
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        try {
            openModelRange(endpoint("/mismatched-end"), offset = 50L, expectedBytes = 100L)
            fail("Expected failure for mismatched ending offset")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Content-Range") == true)
        }
    }

    @Test
    fun mismatchedTotalBytesIn206Fails() {
        server.createContext("/mismatched-total") { exchange ->
            try {
                exchange.responseHeaders.set("Content-Range", "bytes 50-99/101")
                val payload = ByteArray(50)
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        try {
            openModelRange(endpoint("/mismatched-total"), offset = 50L, expectedBytes = 100L)
            fail("Expected failure for mismatched total bytes")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Content-Range") == true)
        }
    }

    @Test
    fun missingContentRangeIn206Fails() {
        server.createContext("/missing-range") { exchange ->
            try {
                val payload = ByteArray(50)
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        try {
            openModelRange(endpoint("/missing-range"), offset = 50L, expectedBytes = 100L)
            fail("Expected failure for missing Content-Range header")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Content-Range") == true)
        }
    }

    @Test
    fun malformedContentRangeIn206Fails() {
        server.createContext("/malformed-range") { exchange ->
            try {
                exchange.responseHeaders.set("Content-Range", "bytes 50-99/*")
                val payload = ByteArray(50)
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        try {
            openModelRange(endpoint("/malformed-range"), offset = 50L, expectedBytes = 100L)
            fail("Expected failure for malformed Content-Range header")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Content-Range") == true)
        }
    }

    @Test
    fun ignoredRangeServerReturns200YieldsAppendFalse() {
        val fullPayload = ByteArray(100) { it.toByte() }

        server.createContext("/ignored-range") { exchange ->
            try {
                exchange.sendResponseHeaders(200, fullPayload.size.toLong())
                exchange.responseBody.use { it.write(fullPayload) }
            } finally {
                exchange.close()
            }
        }

        val response = openModelRange(endpoint("/ignored-range"), offset = 50L, expectedBytes = 100L)
        try {
            assertFalse("Expected append=false when server returns 200", response.append)
            assertArrayEquals(fullPayload, response.input.readBytes())
        } finally {
            response.input.close()
        }
    }

    @Test
    fun offsetZeroSendsNoRangeHeaderAndReturns200WithAppendFalse() {
        val capturedRange = AtomicReference<String?>()
        val capturedEncoding = AtomicReference<String?>()
        val fullPayload = ByteArray(100) { (it * 2).toByte() }

        server.createContext("/offset-zero") { exchange ->
            try {
                capturedRange.set(exchange.requestHeaders.getFirst("Range"))
                capturedEncoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
                exchange.sendResponseHeaders(200, fullPayload.size.toLong())
                exchange.responseBody.use { it.write(fullPayload) }
            } finally {
                exchange.close()
            }
        }

        val response = openModelRange(endpoint("/offset-zero"), offset = 0L, expectedBytes = 100L)
        try {
            assertFalse("Expected append=false for full download", response.append)
            assertNull("Range header should not be sent when offset <= 0", capturedRange.get())
            assertEquals("identity", capturedEncoding.get())
            assertArrayEquals(fullPayload, response.input.readBytes())
        } finally {
            response.input.close()
        }
    }

    @Test
    fun serverReturns404NotFoundFailsSafely() {
        server.createContext("/not-found") { exchange ->
            try {
                exchange.sendResponseHeaders(404, -1)
            } finally {
                exchange.close()
            }
        }

        try {
            openModelRange(endpoint("/not-found"), offset = 0L, expectedBytes = 100L)
            fail("Expected failure for HTTP 404")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("404") == true)
        }
    }

    @Test
    fun serverReturns500InternalErrorFailsSafely() {
        server.createContext("/internal-error") { exchange ->
            try {
                exchange.sendResponseHeaders(500, -1)
            } finally {
                exchange.close()
            }
        }

        try {
            openModelRange(endpoint("/internal-error"), offset = 50L, expectedBytes = 100L)
            fail("Expected failure for HTTP 500")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("500") == true)
        }
    }

    @Test
    fun huggingFaceStyleRedirectPreservesRangeAndFollowsRedirect() {
        val redirectedRange = AtomicReference<String?>()
        val redirectedEncoding = AtomicReference<String?>()
        val payload = ByteArray(50) { (it + 50).toByte() }

        server.createContext("/hf-resolve") { exchange ->
            try {
                exchange.responseHeaders.set("Location", endpoint("/cdn-destination"))
                exchange.sendResponseHeaders(307, -1)
            } finally {
                exchange.close()
            }
        }

        server.createContext("/cdn-destination") { exchange ->
            try {
                redirectedRange.set(exchange.requestHeaders.getFirst("Range"))
                redirectedEncoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
                exchange.responseHeaders.set("Content-Range", "bytes 50-99/100")
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        val response = openModelRange(endpoint("/hf-resolve"), offset = 50L, expectedBytes = 100L)
        try {
            assertTrue("Expected append=true following redirect", response.append)
            assertEquals("bytes=50-", redirectedRange.get())
            assertEquals("identity", redirectedEncoding.get())
            assertArrayEquals(payload, response.input.readBytes())
        } finally {
            response.input.close()
        }
    }

    @Test
    fun closingInputStreamDisconnects() {
        server.createContext("/stream-close") { exchange ->
            try {
                exchange.responseHeaders.set("Content-Range", "bytes 10-99/100")
                val payload = ByteArray(90) { it.toByte() }
                exchange.sendResponseHeaders(206, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }

        val response = openModelRange(endpoint("/stream-close"), offset = 10L, expectedBytes = 100L)
        val stream = response.input
        assertEquals(0, stream.read())
        stream.close()
        try {
            stream.read()
            fail("Expected reading from closed stream to fail")
        } catch (expected: IOException) {
            // Success: closing input stream disconnected/closed the delegate stream
        }
    }
}
