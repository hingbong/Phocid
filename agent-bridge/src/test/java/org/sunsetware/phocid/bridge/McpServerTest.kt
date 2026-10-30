package org.sunsetware.phocid.bridge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class McpServerTest {
    private lateinit var server: McpServer

    @Before
    fun startServer() {
        server = McpServer("127.0.0.1", 0, tokenProvider = { TOKEN })
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun unauthorizedPostReturns401WithoutReadingItsBody() {
        openSocket().use { socket ->
            write(
                socket,
                "POST /mcp HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer wrong-token\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: 1048576\r\n" +
                    "\r\n",
            )
            val response = readResponse(socket, readBody = true)
            assertEquals(401, response.status)
            assertEquals("Bearer", response.headers["www-authenticate"])
        }
    }

    @Test
    fun authorizedPostStillWorks() {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val response =
            request(
                "POST /mcp HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $TOKEN\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${payload.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                    "\r\n" +
                    payload
            )
        assertEquals(200, response.status)
        assertTrue(response.headers.containsKey("mcp-session-id"))
        val text = response.body.toString(StandardCharsets.UTF_8)
        assertTrue(text, text.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(text, text.contains("\"result\""))
    }

    @Test
    fun loopbackHealthNeedsNoAuthorization() {
        val response = request("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals(200, response.status)
        val text = response.body.toString(StandardCharsets.UTF_8)
        assertTrue(text, text.contains("\"status\":\"ok\""))
    }

    @Test
    fun optionsReturnsCorsHeaders() {
        val response = request("OPTIONS /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals(204, response.status)
        assertEquals("*", response.headers["access-control-allow-origin"])
        assertEquals("GET, POST, DELETE, OPTIONS", response.headers["access-control-allow-methods"])
    }

    @Test
    fun oversizedContentLengthIsRejectedAfterAuthWithoutReadingBody() {
        openSocket().use { socket ->
            write(
                socket,
                "POST /mcp HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $TOKEN\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${MAX_BODY_BYTES + 1}\r\n" +
                    "\r\n",
            )
            val response = readResponse(socket, readBody = true)
            assertEquals(413, response.status)
        }
    }

    @Test
    fun unknownPathIsRejectedWithoutReadingBody() {
        openSocket().use { socket ->
            write(
                socket,
                "POST /missing HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Length: ${MAX_BODY_BYTES + 1}\r\n" +
                    "\r\n",
            )
            val response = readResponse(socket, readBody = true)
            assertEquals(404, response.status)
        }
    }

    @Test
    fun unsupportedMethodGets405() {
        val response =
            request(
                "PUT /mcp HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $TOKEN\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n"
            )
        assertEquals(405, response.status)
        assertEquals("GET, POST, DELETE, OPTIONS", response.headers["allow"])
    }

    @Test
    fun authorizedDeleteReturns204() {
        val response =
            request(
                "DELETE /mcp HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $TOKEN\r\n" +
                    "\r\n"
            )
        assertEquals(204, response.status)
    }

    @Test
    fun unauthorizedSseGetReturns401BeforeStreaming() {
        openSocket().use { socket ->
            write(
                socket,
                "GET /mcp HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer wrong-token\r\n" +
                    "Accept: text/event-stream\r\n" +
                    "\r\n",
            )
            val response = readResponse(socket, readBody = true)
            assertEquals(401, response.status)
            assertEquals("Bearer", response.headers["www-authenticate"])
        }
    }

    @Test
    fun sseStreamsAreBoundedAndDoNotUseRequestWorkers() {
        val sockets = ArrayList<Socket>()
        try {
            repeat(OVERLOAD_CONNECTIONS) {
                val socket = openSocket()
                write(
                    socket,
                    "GET /mcp HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Authorization: Bearer $TOKEN\r\n" +
                        "Accept: text/event-stream\r\n" +
                        "\r\n",
                )
                sockets.add(socket)
            }

            var streamed = 0
            var rejected = 0
            var queued = 0
            for (socket in sockets) {
                socket.soTimeout = QUEUED_STREAM_TIMEOUT_MS
                try {
                    val response = readResponse(socket, readBody = false)
                    when (response.status) {
                        200 -> streamed++
                        503 -> rejected++
                        else -> fail("unexpected status ${response.status}")
                    }
                } catch (_: SocketTimeoutException) {
                    // A queued SSE slot holds the connection without writing headers.
                    queued++
                }
            }

            assertTrue("expected an active stream", streamed >= 1)
            assertTrue(
                "expected bounded SSE rejection, got $rejected ($queued queued)",
                rejected >= OVERLOAD_CONNECTIONS - MAX_SSE_STREAMS,
            )
            assertEquals(200, request("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").status)
        } finally {
            sockets.forEach { socket -> runCatching { socket.close() } }
        }
    }

    private fun request(raw: String): Response =
        openSocket().use { socket ->
            write(socket, raw)
            readResponse(socket, readBody = true)
        }

    private fun openSocket(): Socket =
        Socket().apply {
            connect(InetSocketAddress("127.0.0.1", server.boundPort), CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
        }

    private fun write(socket: Socket, request: String) {
        socket.getOutputStream().apply {
            write(request.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun readResponse(socket: Socket, readBody: Boolean): Response {
        val input = socket.getInputStream()
        val statusLine = readLine(input) ?: error("missing status line")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("bad status line")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: error("unexpected end of headers")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body =
            if (readBody) {
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    if (count < 0) break
                    offset += count
                }
                bytes.copyOf(offset)
            } else {
                ByteArray(0)
            }
        return Response(status, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (output.size() == 0) null
                else String(output.toByteArray(), StandardCharsets.US_ASCII)
            }
            if (value == '\n'.code) {
                return String(output.toByteArray(), StandardCharsets.US_ASCII)
            }
            if (value != '\r'.code) {
                output.write(value)
            }
        }
    }

    private data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private companion object {
        private const val TOKEN = "test-token"
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_SSE_STREAMS = 4
        private const val OVERLOAD_CONNECTIONS = 12
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val QUEUED_STREAM_TIMEOUT_MS = 2_000
    }
}
