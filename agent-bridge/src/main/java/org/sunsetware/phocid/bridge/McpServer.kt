package org.sunsetware.phocid.bridge

import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A deliberately small MCP server for the local OpenMinis HTTP transport.
 *
 * It only listens on 127.0.0.1. The peer is authenticated with a bearer token. The first version
 * exposes a single ping tool; real library and statistics tools are added after the transport is
 * verified against the agent app.
 */
class McpServer(
    private val port: Int,
    private val tokenProvider: () -> String,
    private val dataSource: PhocidDataSource? = null,
) {
    private val running = AtomicBoolean(false)
    private val startedAt = AtomicLong(0)
    private val heartbeatCount = AtomicLong(0)
    private val lastHeartbeatAt = AtomicLong(0)
    private val executor = Executors.newCachedThreadPool()
    private var heartbeatThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            serverSocket = socket
            startedAt.set(SystemClock.elapsedRealtime())
            Thread({ acceptLoop(socket) }, "mcp-accept").start()
            startHeartbeat()
        } catch (t: Throwable) {
            running.set(false)
            throw t
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        executor.shutdownNow()
    }

    private fun startHeartbeat() {
        heartbeatThread =
            Thread(
                {
                    while (running.get()) {
                        heartbeatCount.incrementAndGet()
                        lastHeartbeatAt.set(SystemClock.elapsedRealtime())
                        try {
                            Thread.sleep(1_000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                },
                "mcp-heartbeat",
            ).apply {
                isDaemon = true
                start()
            }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                executor.execute { handle(client) }
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept failed", e)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 15_000
                try {
                    route(socket)
                } catch (e: HttpError) {
                    safeWrite(
                        socket,
                        e.status,
                        "text/plain; charset=utf-8",
                        e.message.toByteArray(StandardCharsets.UTF_8),
                    )
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "client closed", e)
        } catch (e: Exception) {
            Log.e(TAG, "request failed", e)
        }
    }

    private fun route(socket: Socket) {
        val request = readRequest(socket)
        val path = request.path.trimEnd('/').ifEmpty { "/" }

        if (request.method == "OPTIONS") {
            safeWrite(socket, 204, null, null, corsHeaders())
            return
        }

        if (path == "/health" && request.method == "GET") {
            val body =
                JSONObject()
                    .put("status", "ok")
                    .put("port", port)
                    .put("stateless", true)
                    .put("uptimeMs", SystemClock.elapsedRealtime() - startedAt.get())
                    .put("heartbeatCount", heartbeatCount.get())
                    .put(
                        "heartbeatLagMs",
                        lastHeartbeatAt.get().let { last ->
                            if (last == 0L) 0L else SystemClock.elapsedRealtime() - last
                        },
                    )
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)
            safeWrite(socket, 200, "application/json; charset=utf-8", body)
            return
        }

        if (path != "/mcp") {
            throw HttpError(404, "Not found")
        }

        if (!isAuthorized(request)) {
            safeWrite(
                socket,
                401,
                "text/plain; charset=utf-8",
                "Unauthorized".toByteArray(StandardCharsets.UTF_8),
                mapOf("WWW-Authenticate" to "Bearer"),
            )
            return
        }

        when (request.method) {
            "POST" -> handlePost(socket, request)
            "GET" -> handleGet(socket, request)
            "DELETE" -> safeWrite(socket, 204, null, null)
            else -> {
                safeWrite(
                    socket,
                    405,
                    "text/plain; charset=utf-8",
                    "Method not allowed".toByteArray(StandardCharsets.UTF_8),
                    mapOf("Allow" to "GET, POST, DELETE, OPTIONS"),
                )
            }
        }
    }

    private fun handlePost(socket: Socket, request: Request) {
        val sessionId = request.headers["mcp-session-id"] ?: UUID.randomUUID().toString()

        val payload = request.body.toString(StandardCharsets.UTF_8)
        if (payload.isBlank()) {
            throw HttpError(400, "Empty body")
        }

        val response =
            try {
                processPayload(payload)
            } catch (_: JSONException) {
                jsonRpcError(null, -32700, "Parse error").toString()
            } catch (_: Exception) {
                jsonRpcError(null, -32603, "Internal error").toString()
            }

        if (response == null) {
            safeWrite(socket, 202, null, null, mapOf("Mcp-Session-Id" to sessionId))
        } else {
            safeWrite(
                socket,
                200,
                "application/json; charset=utf-8",
                response.toByteArray(StandardCharsets.UTF_8),
                mapOf("Mcp-Session-Id" to sessionId),
            )
        }
    }

    private fun handleGet(socket: Socket, request: Request) {
        val accept = request.headers["accept"] ?: ""
        if (!accept.contains("text/event-stream")) {
            safeWrite(
                socket,
                405,
                "text/plain; charset=utf-8",
                "Use POST for JSON-RPC or Accept: text/event-stream".toByteArray(
                    StandardCharsets.UTF_8
                ),
                mapOf("Allow" to "POST, DELETE, OPTIONS"),
            )
            return
        }

        val sessionId = request.headers["mcp-session-id"] ?: UUID.randomUUID().toString()
        socket.soTimeout = 0
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Mcp-Session-Id: $sessionId\r\n" +
                    "\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        )
        out.write(": connected\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
        try {
            while (running.get() && !socket.isClosed) {
                Thread.sleep(15_000)
                out.write(": ping\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IOException) {}
    }

    private fun processPayload(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.startsWith("[")) {
            val input = JSONArray(trimmed)
            val output = JSONArray()
            for (i in 0 until input.length()) {
                val item = input.optJSONObject(i) ?: continue
                processObject(item)?.let { output.put(it) }
            }
            return if (output.length() == 0) null else output.toString()
        }
        return processObject(JSONObject(trimmed))?.toString()
    }

    private fun processObject(request: JSONObject): JSONObject? {
        val id = request.opt("id")
        val method = request.optString("method", "")
        if (method.isBlank()) {
            return jsonRpcError(id, -32600, "Invalid Request")
        }
        if (!request.has("id")) {
            return null
        }

        return when (method) {
            "initialize" -> jsonRpcResult(id, initializeResult(request.optJSONObject("params")))
            "ping" -> jsonRpcResult(id, JSONObject())
            "tools/list" -> jsonRpcResult(id, toolsList())
            "tools/call" -> jsonRpcResult(id, toolsCall(request.optJSONObject("params")))
            "resources/list" -> jsonRpcResult(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> jsonRpcResult(id, JSONObject().put("prompts", JSONArray()))
            else -> jsonRpcError(id, -32601, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JSONObject?): JSONObject {
        val requested =
            params?.optString("protocolVersion")?.takeIf { it.isNotBlank() } ?: "2025-03-26"
        return JSONObject()
            .put("protocolVersion", requested)
            .put(
                "capabilities",
                JSONObject().put("tools", JSONObject().put("listChanged", false)),
            )
            .put(
                "serverInfo",
                JSONObject().put("name", "phocid-agent-bridge").put("version", "0.3.0"),
            )
    }

    private fun toolsList(): JSONObject {
        val tools = JSONArray()
        tools.put(
            tool(
                "ping",
                "Health check for the Phocid agent bridge. Returns pong.",
                emptySchema(),
            )
        )
        tools.put(
            tool(
                "phocid_capabilities",
                "Read the Phocid agent API version, library revision, time zone, and supported ranges.",
                emptySchema(),
            )
        )
        tools.put(
            tool(
                "library_search_tracks",
                "Search the Phocid library by tag fields. Returns a page of track metadata.",
                searchSchema(),
            )
        )
        tools.put(
            tool(
                "library_get_track",
                "Read every exposed tag field for one library track, including its stats UUID when resolvable.",
                libraryIdSchema(),
            )
        )
        tools.put(
            tool(
                "library_list_facets",
                "List artists, albums, album artists, or genres with optional filtering and pagination.",
                facetSchema(),
            )
        )
        tools.put(
            tool(
                "player_state",
                "Read the current player state, including live position when the playback service is running.",
                emptySchema(),
            )
        )
        tools.put(
            tool(
                "stats_summary",
                "Read playback totals for a range: plays, millis, unique tracks, and active days.",
                rangeSchema(),
            )
        )
        tools.put(
            tool(
                "stats_top_tracks",
                "Read the top played tracks for a range.",
                topTracksSchema(),
            )
        )
        tools.put(
            tool(
                "stats_daily",
                "Read the daily playback trend for a range.",
                rangeSchema(),
            )
        )
        tools.put(
            tool(
                "resolve_stats_uuid",
                "Resolve a library track id to its portable statistics UUID.",
                libraryIdSchema(),
            )
        )
        return JSONObject().put("tools", tools)
    }

    private fun toolsCall(params: JSONObject?): JSONObject {
        val name = params?.optString("name").orEmpty()
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        val source = dataSource
        return when (name) {
            "ping" -> toolResult("pong")
            "phocid_capabilities" -> dataResult(source?.capabilities())
            "library_list_facets" -> dataResult(source?.listFacets(args.toString()))
            "player_state" -> dataResult(source?.playerState())
            "library_search_tracks" -> dataResult(source?.searchTracks(args.toString()))
            "library_get_track" ->
                dataResult(source?.getTrack(args.optLong("libraryId", -1L)))
            "stats_summary" ->
                dataResult(source?.statsSummary(args.optString("range", "30d")))
            "stats_top_tracks" -> dataResult(source?.statsTopTracks(args.toString()))
            "stats_daily" ->
                dataResult(source?.statsDaily(args.optString("range", "30d")))
            "resolve_stats_uuid" ->
                dataResult(source?.resolveStatsUuid(args.optLong("libraryId", -1L)))
            else -> toolResult("Unknown tool: $name", isError = true)
        }
    }

    private fun dataResult(value: String?): JSONObject {
        if (value == null) {
            return toolResult("Phocid data service is not available", isError = true)
        }
        val isError =
            try {
                JSONObject(value).has("error")
            } catch (_: Exception) {
                false
            }
        return toolResult(value, isError)
    }

    private fun emptySchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false)

    private fun libraryIdSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put("libraryId", integerProp("Phocid library track id")),
            )
            .put("required", JSONArray().put("libraryId"))
            .put("additionalProperties", false)

    private fun rangeSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put("range", rangeProp()),
            )
            .put("additionalProperties", false)

    private fun topTracksSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("range", rangeProp())
                    .put("sort", stringProp("plays or time"))
                    .put("limit", integerProp("1..200, default 20")),
            )
            .put("additionalProperties", false)

    private fun facetSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "kind",
                        stringProp("artists, albums, album_artists, or genres")
                            .put(
                                "enum",
                                JSONArray().apply {
                                    listOf("artists", "albums", "album_artists", "genres")
                                        .forEach { put(it) }
                                },
                            ),
                    )
                    .put("query", stringProp("Case-insensitive name filter"))
                    .put("limit", integerProp("1..200, default 50"))
                    .put("offset", integerProp("Pagination offset")),
            )
            .put("required", JSONArray().put("kind"))
            .put("additionalProperties", false)

    private fun searchSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("query", stringProp("Full-text match across title, artist, album, genre, and file name"))
                    .put("title", stringProp("Title contains"))
                    .put("artist", stringProp("Any artist contains"))
                    .put("album", stringProp("Album contains"))
                    .put("albumArtist", stringProp("Any album artist contains"))
                    .put("genre", stringProp("Any genre contains"))
                    .put("year", integerProp("Exact year"))
                    .put("yearFrom", integerProp("Minimum year"))
                    .put("yearTo", integerProp("Maximum year"))
                    .put("format", stringProp("Container format, case-insensitive"))
                    .put("hasArtwork", booleanProp("Filter by embedded artwork"))
                    .put("minDurationMs", integerProp("Minimum duration in milliseconds"))
                    .put("maxDurationMs", integerProp("Maximum duration in milliseconds"))
                    .put("sort", stringProp("title, artist, album, year, dateAdded, or duration"))
                    .put("descending", booleanProp("Reverse the sort order"))
                    .put("limit", integerProp("1..200, default 50"))
                    .put("offset", integerProp("Pagination offset")),
            )
            .put("additionalProperties", false)

    private fun rangeProp(): JSONObject =
        stringProp("7d, 30d, month, 90d, 365d, year, 3y, or all")
            .put(
                "enum",
                JSONArray().apply {
                    listOf("7d", "30d", "month", "90d", "365d", "year", "3y", "all")
                        .forEach { put(it) }
                },
            )

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun integerProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun booleanProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun tool(name: String, description: String, inputSchema: JSONObject): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", inputSchema)

    private fun toolResult(text: String, isError: Boolean = false): JSONObject =
        JSONObject()
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", text)),
            )
            .put("isError", isError)

    private fun jsonRpcResult(id: Any?, result: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)

    private fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject().put("code", code).put("message", message),
            )

    private fun isAuthorized(request: Request): Boolean {
        val actual = request.headers["authorization"]?.trim() ?: return false
        val expected = "Bearer " + tokenProvider()
        return MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readRequest(socket: Socket): Request {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: throw HttpError(400, "Missing request line")
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) throw HttpError(400, "Malformed request line")
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val path = target.substringBefore('?')

        val headers = LinkedHashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: throw HttpError(400, "Unexpected end of headers")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) throw HttpError(413, "Headers too large")
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            headers[key] = headers[key]?.let { "$it, $value" } ?: value
        }

        val body =
            when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                    readChunked(input)
                else -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length < 0) throw HttpError(400, "Invalid Content-Length")
                    if (length > MAX_BODY_BYTES) throw HttpError(413, "Body too large")
                    val bytes = ByteArray(length)
                    readFully(input, bytes)
                    bytes
                }
            }

        return Request(method, path, headers, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw HttpError(400, "Unexpected end of chunks")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
            if (size == null || size < 0) throw HttpError(400, "Invalid chunk size")
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (output.size() + size > MAX_BODY_BYTES) throw HttpError(413, "Body too large")
            val chunk = ByteArray(size)
            readFully(input, chunk)
            output.write(chunk)
            val crlf = readLine(input) ?: throw HttpError(400, "Unexpected end of chunk")
            if (crlf.isNotEmpty()) throw HttpError(400, "Malformed chunk")
        }
        return output.toByteArray()
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw HttpError(400, "Unexpected end of body")
            offset += read
        }
    }

    private fun readLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (output.size() == 0) null
                else String(output.toByteArray(), StandardCharsets.UTF_8)
            }
            if (value == '\n'.code) {
                return String(output.toByteArray(), StandardCharsets.UTF_8)
            }
            if (value != '\r'.code) {
                output.write(value)
            }
            if (output.size() > MAX_LINE_BYTES) throw HttpError(413, "Header line too long")
        }
    }

    private fun safeWrite(
        socket: Socket,
        status: Int,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        try {
            writeResponse(socket, status, contentType, body, extraHeaders)
        } catch (_: IOException) {}
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val header = StringBuilder()
        header.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        if (contentType != null) {
            header.append("Content-Type: ").append(contentType).append("\r\n")
        }
        header.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        header.append("Connection: close\r\n")
        header.append("Cache-Control: no-store\r\n")
        extraHeaders.forEach { (key, value) ->
            header.append(key).append(": ").append(value).append("\r\n")
        }
        header.append("\r\n")
        out.write(header.toString().toByteArray(StandardCharsets.US_ASCII))
        if (body != null && body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun statusText(status: Int): String =
        when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }

    private fun corsHeaders(): Map<String, String> =
        mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
            "Access-Control-Allow-Headers" to "Authorization, Content-Type, Mcp-Session-Id",
        )

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class HttpError(val status: Int, override val message: String) : Exception(message)

    companion object {
        private const val TAG = "PhocidMcpServer"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
    }
}
