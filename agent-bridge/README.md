# Phocid Agent Bridge

A small companion APK that exposes Phocid data to an on-device MCP client over a
local-only HTTP endpoint.

## Why a separate APK

Phocid's main APK has no `INTERNET` permission. The bridge owns:

- `INTERNET`
- the MCP/HTTP server
- the bearer token

The main APK exposes a read-only Binder query API to the bridge. The bridge
translates MCP tool calls into Binder calls. The agent app never needs Android
native integration; it only sees a standard MCP HTTP endpoint.

## Current state

It exposes:

- `GET /health`
  - `status`, `port`, `stateless`
  - `uptimeMs`, `heartbeatCount`, `heartbeatLagMs`
- `POST /mcp` (Streamable HTTP JSON-RPC)
- `GET /mcp` (SSE stream, ping comments)
- `DELETE /mcp` (session teardown)

MCP tools:

- `ping`
- `phocid_capabilities` (includes library counts)
- `library_search_tracks`
- `library_get_track`
- `library_list_facets` (artists, albums, album_artists, genres)
- `stats_summary`
- `stats_top_tracks`
- `stats_daily`
- `resolve_stats_uuid`
- `player_state` (read-only, optional live position)

The service uses `START_STICKY`, `stopWithTask=false`, an ongoing
notification, and a notification Stop action. The UI also shows whether the
app is exempt from battery optimization.

`player_state` distinguishes `active` (PlaybackService is running) from
`isPlaying`. It reads a `MediaController` only when the service is already
running; if the service is not running it returns `active: false` and
`positionMs: null` without starting playback. It never calls play, pause, seek,
or any queue mutation.

The main app reports `apiVersion: 2`. Newer bridge tools return
`unsupported_api` when the installed main APK is older.

## Build

```bash
./gradlew :agent-bridge:assembleRelease
```

The release build uses the same signing config as the main app. Both APKs must
be signed with the same certificate before the Binder signature permission can
work.

## Use

1. Install `agent-bridge-release.apk`.
2. Open **Phocid Agent Bridge** and tap **Start**.
3. In OpenMinis, add an MCP server:

   - Name: `Phocid`
   - Type: `HTTP`
   - URL: `http://127.0.0.1:8765/mcp`
   - Header: `Authorization: Bearer <token>`
   - Authorization: `None`
   - Note: `Read-only Phocid library tags and playback statistics.`

4. Keep the bridge foreground service running while the agent uses it.
5. If the bridge is reported as "Optimized", open **Battery settings** and set
   it to unrestricted. Some OEM ROMs freeze foreground services anyway; the
   heartbeat fields below make that visible after the fact.

## Debug with curl

```bash
adb forward tcp:8765 tcp:8765
curl -s http://127.0.0.1:8765/health

curl -s \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' \
  http://127.0.0.1:8765/mcp
```

The response should include `serverInfo.name = phocid-agent-bridge` and a
`Mcp-Session-Id` response header.

## Freeze diagnostics

A frozen process cannot answer `/health`. After it wakes, compare:

- `uptimeMs` — total elapsed time since the server started.
- `heartbeatCount` — expected to be roughly `uptimeMs / 1000`.
- `heartbeatLagMs` — normally close to 0.

If `heartbeatCount` is much lower than `uptimeMs / 1000`, or
`heartbeatLagMs` is large, the bridge process was not scheduled for a while.
That is an Android/OEM freeze, not an HTTP bug.

If `/health` itself times out while the client is alive, run the probe from
another process (PC via `adb forward`, or a second device) to distinguish a
frozen client from a frozen server.

## Security

- The server binds only to `127.0.0.1`.
- Every `/mcp` request requires the bearer token.
- The token is stored in the bridge's private SharedPreferences.
- The bridge does not make outbound network requests.
- The bridge is a separate UID; the main APK still has no `INTERNET` permission.

## API version

The main app currently reports `apiVersion: 2`. The bridge caches this value
and rejects `library_list_facets` / `player_state` with `unsupported_api`
when an older main APK is installed.

## Out of scope

Playback control, seek, queue mutation, playlist editing, lyrics, and file
paths are intentionally not exposed.
