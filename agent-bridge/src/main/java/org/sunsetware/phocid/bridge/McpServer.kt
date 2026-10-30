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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A deliberately small MCP server for the local OpenMinis HTTP transport.
 *
 * The listen address defaults to 127.0.0.1 and can be changed in the bridge UI. The peer is
 * authenticated with a bearer token. The first version exposes a single ping tool; real library
 * and statistics tools are added after the transport is verified against the agent app.
 */
class McpServer(
    private val host: String,
    private val port: Int,
    private val tokenProvider: () -> String,
    private val dataSource: PhocidDataSource? = null,
    private val allowWrites: Boolean = false,
) {
    private val running = AtomicBoolean(false)
    private val startedAt = AtomicLong(0)
    private val heartbeatCount = AtomicLong(0)
    private val lastHeartbeatAt = AtomicLong(0)
    private val requestExecutor =
        ThreadPoolExecutor(
            REQUEST_CORE_POOL_SIZE,
            REQUEST_MAX_POOL_SIZE,
            THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(REQUEST_QUEUE_CAPACITY),
            daemonThreadFactory("mcp-request"),
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val sseExecutor =
        ThreadPoolExecutor(
            SSE_CORE_POOL_SIZE,
            SSE_MAX_POOL_SIZE,
            THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(SSE_QUEUE_CAPACITY),
            daemonThreadFactory("mcp-sse"),
            ThreadPoolExecutor.AbortPolicy(),
        )
    private var heartbeatThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var loopbackBind = false

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            require(BridgeEndpoint.isNumericHost(host)) { "Listen address must be an IP address" }
            val address = InetAddress.getByName(host)
            loopbackBind = address.isLoopbackAddress
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 50)
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
        shutdownAndClosePending(requestExecutor)
        shutdownAndClosePending(sseExecutor)
    }

    private fun shutdownAndClosePending(executor: ThreadPoolExecutor) {
        for (task in executor.shutdownNow()) {
            (task as? ClientTask)?.closePending()
        }
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
                try {
                    requestExecutor.execute(ClientTask(client) { handle(it) })
                } catch (_: RejectedExecutionException) {
                    if (running.get()) rejectOverloaded(client) else closeQuietly(client)
                }
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept failed", e)
            }
        }
    }

    private fun handle(client: Socket) {
        var transferred = false
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequestHead(input)
            transferred = route(client, input, request)
        } catch (e: HttpError) {
            safeWrite(
                client,
                e.status,
                "text/plain; charset=utf-8",
                e.message.toByteArray(StandardCharsets.UTF_8),
            )
        } catch (e: IOException) {
            Log.d(TAG, "client closed", e)
        } catch (e: Exception) {
            Log.e(TAG, "request failed", e)
        } finally {
            if (!transferred) closeQuietly(client)
        }
    }

    private fun route(socket: Socket, input: InputStream, request: Request): Boolean {
        val path = request.path.trimEnd('/').ifEmpty { "/" }

        if (request.method == "OPTIONS") {
            safeWrite(socket, 204, null, null, corsHeaders())
            return false
        }

        if (path == "/health" && request.method == "GET") {
            if (!loopbackBind && !isAuthorized(request)) {
                unauthorized(socket)
                return false
            }
            val body =
                JSONObject()
                    .put("status", "ok")
                    .put("host", host)
                    .put("port", port)
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("commit", BuildConfig.BUILD_COMMIT.take(12))
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
            return false
        }

        if (path != "/mcp") {
            throw HttpError(404, "Not found")
        }

        if (!isAuthorized(request)) {
            unauthorized(socket)
            return false
        }

        when (request.method) {
            "POST" -> handlePost(socket, input, request)
            "GET" -> return handleGet(socket, request)
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
        return false
    }

    private fun unauthorized(socket: Socket) {
        safeWrite(
            socket,
            401,
            "text/plain; charset=utf-8",
            "Unauthorized".toByteArray(StandardCharsets.UTF_8),
            mapOf("WWW-Authenticate" to "Bearer"),
        )
    }

    private fun handlePost(socket: Socket, input: InputStream, request: Request) {
        val body = readBody(input, request.headers)
        val sessionId = request.headers["mcp-session-id"] ?: UUID.randomUUID().toString()

        val payload = body.toString(StandardCharsets.UTF_8)
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

    private fun handleGet(socket: Socket, request: Request): Boolean {
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
            return false
        }

        try {
            sseExecutor.execute(ClientTask(socket) { streamSse(it, request) })
        } catch (_: RejectedExecutionException) {
            safeWrite(
                socket,
                503,
                "text/plain; charset=utf-8",
                "Too many event streams".toByteArray(StandardCharsets.UTF_8),
                mapOf("Retry-After" to "1"),
            )
            return false
        }
        return true
    }

    private fun streamSse(socket: Socket, request: Request) {
        try {
            socket.soTimeout = 0
            val sessionId = request.headers["mcp-session-id"] ?: UUID.randomUUID().toString()
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
            while (running.get() && !socket.isClosed) {
                Thread.sleep(SSE_PING_INTERVAL_MS)
                out.write(": ping\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IOException) {
        } finally {
            closeQuietly(socket)
        }
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
                JSONObject()
                    .put("name", "phocid-agent-bridge")
                    .put("version", BuildConfig.VERSION_NAME),
            )
    }

    private fun toolsList(): JSONObject {
        val capabilities = capabilitiesJson()
        val apiVersion = capabilities?.optInt("apiVersion", 1) ?: 1
        val api5 = apiVersion >= 5
        val sortKeysAvailable =
            apiVersion >= 4 && capabilities?.optBoolean("writeSortKeys", false) == true
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
                if (api5) {
                    "Search the Phocid library by tag fields (API 5). Returns a byte-bounded page of " +
                        "track summaries without comment or unsyncedLyrics; call library_get_track " +
                        "for full tags. Check byteTruncated and continue from nextOffset."
                } else {
                    "Search the Phocid library by tag fields. Returns a page of track metadata."
                },
                searchSchema(api5),
            )
        )
        tools.put(
            tool(
                "library_get_track",
                if (api5) {
                    "Read every exposed tag field for one library track, including comment, " +
                        "unsyncedLyrics, and its stats UUID when resolvable. If the detail exceeds " +
                        "the 512 KiB response budget, heavy fields are omitted and listed in " +
                        "truncatedFields."
                } else {
                    "Read every exposed tag field for one library track, including its stats UUID when resolvable."
                },
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
                if (api5) {
                    "Read the top played tracks for a range. The response is byte-bounded; check " +
                        "byteTruncated and continue from nextOffset."
                } else {
                    "Read the top played tracks for a range."
                },
                topTracksSchema(api5),
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
        if (sortKeysAvailable) {
            tools.put(
                tool(
                    "library_validate_sort_keys",
                    "Dry-run sort-key updates and report what would be rejected. Does not write anything.",
                    sortKeysSchema(),
                )
            )
        }
        if (allowWrites && sortKeysAvailable) {
            tools.put(
                tool(
                    "library_update_sort_keys",
                    "Set or clear sort keys for library titles. Does not modify audio files or tags.",
                    sortKeysSchema(),
                )
            )
        }
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
            "library_validate_sort_keys" ->
                if (!canSortKeys()) {
                    toolResult("The main app is too old for sort keys", isError = true)
                } else {
                    val request = JSONObject(args.toString()).put("dryRun", true)
                    dataResult(source?.updateSortKeys(request.toString()))
                }
            "library_update_sort_keys" ->
                if (!canWrite()) {
                    toolResult("Writes are disabled or the main app is too old", isError = true)
                } else {
                    dataResult(source?.updateSortKeys(args.toString()))
                }
            else -> toolResult("Unknown tool: $name", isError = true)
        }
    }

    private fun canSortKeys(): Boolean {
        val capabilities = dataSource?.capabilities() ?: return false
        return runCatching {
                val json = JSONObject(capabilities)
                json.optInt("apiVersion", 1) >= 4 && json.optBoolean("writeSortKeys", false)
            }
            .getOrDefault(false)
    }

    private fun canWrite(): Boolean = allowWrites && canSortKeys()

    /** Reads the main app capabilities once, treating errors or a missing service as empty. */
    private fun capabilitiesJson(): JSONObject? {
        val capabilities =
            try {
                dataSource?.capabilities()
            } catch (_: Exception) {
                null
            } ?: return null
        return runCatching { JSONObject(capabilities) }.getOrNull()
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

    private fun sortKeysSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 1000)
                            .put(
                                "description",
                                "Only the first 200 entries are processed; the response reports the remainder",
                            )
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put(
                                                "libraryId",
                                                integerProp("Phocid library track id"),
                                            )
                                            .put(
                                                "sortKey",
                                                JSONObject()
                                                    .put(
                                                        "anyOf",
                                                        JSONArray()
                                                            .put(
                                                                JSONObject()
                                                                    .put("type", "string")
                                                            )
                                                            .put(
                                                                JSONObject()
                                                                    .put("type", "null")
                                                            ),
                                                    ),
                                            ),
                                    )
                                    .put(
                                        "required",
                                        JSONArray().put("libraryId").put("sortKey"),
                                    )
                                    .put("additionalProperties", false),
                            ),
                    )
                    .put("overwrite", booleanProp("Overwrite existing values; default false")),
            )
            .put("required", JSONArray().put("items"))
            .put("additionalProperties", false)

    private fun rangeSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put("range", rangeProp()),
            )
            .put("additionalProperties", false)

    private fun topTracksSchema(api5: Boolean): JSONObject {
        val properties =
            JSONObject()
                .put("range", rangeProp())
                .put("sort", stringProp("plays or time"))
                .put("limit", integerProp("1..200, default 20"))
        if (api5) {
            properties.put(
                "offset",
                integerProp("Pagination offset; continue from the response nextOffset"),
            )
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)
    }

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

    private fun searchSchema(api5: Boolean): JSONObject {
        val schema = JSONObject().put("type", "object")
        if (api5) {
            schema.put(
                "description",
                "API 5 search returns summaries with libraryId, fileName, title, artists, album, " +
                    "albumArtists, genres, year, originalYear, trackNumber, discNumber, " +
                    "durationMs, size, format, sampleRate, bitRate, bitDepth, hasArtwork, " +
                    "hasSortKey, sortKey, and dateAdded. comment and unsyncedLyrics are only " +
                    "returned by library_get_track, which lists them in truncatedFields when " +
                    "they are omitted. Search responses use a 512 KiB UTF-16 JSON budget and " +
                    "include byteTruncated and nextOffset.",
            )
        }
        schema
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
                    .put("hasSortKey", booleanProp("Filter by presence of a sort key"))
                    .put("minDurationMs", integerProp("Minimum duration in milliseconds"))
                    .put("maxDurationMs", integerProp("Maximum duration in milliseconds"))
                    .put(
                        "sort",
                        stringProp(
                                "title, rawTitle, sortKey, artist, album, year, dateAdded, or duration"
                            )
                            .put(
                                "enum",
                                JSONArray().apply {
                                    listOf(
                                            "title",
                                            "rawTitle",
                                            "sortKey",
                                            "artist",
                                            "album",
                                            "year",
                                            "dateAdded",
                                            "duration",
                                        )
                                        .forEach { put(it) }
                                },
                            ),
                    )
                    .put("descending", booleanProp("Reverse the sort order"))
                    .put(
                        "limit",
                        integerProp(
                            if (api5) {
                                "1..200, default 50; the page stops early with byteTruncated when " +
                                    "the 512 KiB response budget is reached"
                            } else {
                                "1..200, default 50"
                            }
                        ),
                    )
                    .put(
                        "offset",
                        integerProp(
                            if (api5) {
                                "Pagination offset; continue from the response nextOffset"
                            } else {
                                "Pagination offset"
                            }
                        ),
                    ),
            )
            .put("additionalProperties", false)
        return schema
    }

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

    private fun readRequestHead(input: InputStream): Request {
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

        return Request(method, path, headers)
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray =
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
            if (size > MAX_BODY_BYTES || size > MAX_BODY_BYTES - output.size()) {
                throw HttpError(413, "Body too large")
            }
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

    private fun rejectOverloaded(client: Socket) {
        try {
            client.soTimeout = REJECT_SOCKET_TIMEOUT_MS
            safeWrite(
                client,
                503,
                "text/plain; charset=utf-8",
                "Server busy".toByteArray(StandardCharsets.UTF_8),
                mapOf("Retry-After" to "1"),
            )
        } finally {
            closeQuietly(client)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
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
            503 -> "Service Unavailable"
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
    )

    private class HttpError(val status: Int, override val message: String) : Exception(message)

    private inner class ClientTask(
        private val socket: Socket,
        private val handler: (Socket) -> Unit,
    ) : Runnable {
        override fun run() {
            handler(socket)
        }

        fun closePending() {
            closeQuietly(socket)
        }
    }

    companion object {
        private const val TAG = "PhocidMcpServer"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val SSE_PING_INTERVAL_MS = 15_000L
        private const val REJECT_SOCKET_TIMEOUT_MS = 3_000
        private const val THREAD_KEEP_ALIVE_SECONDS = 30L
        private const val REQUEST_CORE_POOL_SIZE = 2
        private const val REQUEST_MAX_POOL_SIZE = 4
        private const val REQUEST_QUEUE_CAPACITY = 16
        private const val SSE_CORE_POOL_SIZE = 1
        private const val SSE_MAX_POOL_SIZE = 2
        private const val SSE_QUEUE_CAPACITY = 2

        private fun daemonThreadFactory(prefix: String): ThreadFactory {
            val counter = AtomicInteger(0)
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }
}
