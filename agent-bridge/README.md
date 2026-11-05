# Phocid Agent Bridge

A small companion APK that exposes Phocid data to an on-device MCP client over a
local HTTP endpoint. It listens on loopback by default; the listen address and
port can be changed in the app.

## Why a separate APK

Phocid's main APK has no `INTERNET` permission. The bridge owns:

- `INTERNET`
- `ACCESS_LOCAL_NETWORK` (Android 17+ only; requested at runtime)
- the MCP/HTTP server
- the bearer token

The main APK exposes a read-only Binder query API to the bridge, plus one
opt-in method for local sort keys. The bridge translates MCP tool calls into
Binder calls. The agent app never needs Android native integration; it only
sees a standard MCP HTTP endpoint.

Android 17 (API 37) made local network access a runtime permission: inbound
connections on a non-loopback address are blocked without it, while loopback
and `adb forward` keep working. The bridge declares `ACCESS_LOCAL_NETWORK`,
asks for it when you start on such an address, and refuses to start instead of
silently accepting connections that can never arrive. Android 16 and earlier
grant local network access implicitly through `INTERNET` and do not know the
permission, so the bridge skips the check and the request there.

## Current state

It exposes:

- `GET /health`
  - `status`, `host`, `port`, `version`, `commit`, `stateless`
  - `uptimeMs`, `heartbeatCount`, `heartbeatLagMs`
  - The bearer token is required when the server is bound to a non-loopback
    address.
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
- `stats_tracks`
- `stats_daily`
- `resolve_stats_uuid`
- `player_state` (read-only, optional live position)
- `library_validate_sort_keys` (dry-run; validates without writing)
- `library_update_sort_keys` (opt-in; stores local sort keys only)

The service uses `START_STICKY`, `stopWithTask=false`, an ongoing
notification, and a notification Stop action. The UI also shows whether the
app is exempt from battery optimization.

`player_state` distinguishes `active` (PlaybackService is running) from
`isPlaying`. It reads a `MediaController` only when the service is already
running; if the service is not running it returns `active: false` and
`positionMs: null` without starting playback. It never calls play, pause, seek,
or any queue mutation.

`positionMs` is a snapshot. `sampledAtElapsedRealtimeMs` is
`SystemClock.elapsedRealtime()`: milliseconds since device boot, including deep
sleep. It is not a Unix timestamp and not bridge process uptime. `positionAgeMs`
is how old that snapshot was when the response was built; clients should add it
to `positionMs` to estimate the current position. `currentTrack.trackRef` carries
the library id and, when resolvable, the statistics UUID.

The main app reports `apiVersion: 6`. `library_list_facets` requires API 2;
`player_state` requires API 3; `library_validate_sort_keys` and
`library_update_sort_keys` require API 4; `stats_tracks` and the API 6 stats
fields require API 6. Search and track detail require API 1. Older main APKs
return `unsupported_api`.

## Search and detail payloads (API 6)

`library_search_tracks` returns lightweight summaries with these fields:
`trackRef` (`libraryId`, `statsUuid` when resolved), `fileName`, `title`,
`artists`, `album`, `albumArtists`, `genres`, `year`, `originalYear`,
`trackNumber`, `discNumber`, `durationMs`, `size`, `format`, `sampleRate`,
`bitRate`, `bitDepth`, `hasArtwork`, `hasSortKey`, `sortKey`, `dateAddedMs`,
and `dateAddedIso`. `comment` and `unsyncedLyrics` are not included in search
results.

`library_get_track` keeps the full track payload, including `comment` and
`unsyncedLyrics`. If that payload would exceed the 512 KiB UTF-16 JSON budget,
the heavy fields are omitted and the response gains
`truncatedFields: ["comment", "unsyncedLyrics"]` instead of failing the Binder
call.

Search responses are bounded to a conservative 512 KiB UTF-16 JSON budget. The
page stops before the first track that would exceed it. `nextOffset` points at
the next unreturned track, and `byteTruncated` is true only when the budget (not
the requested `limit`) stopped the page. A single summary that cannot fit
returns a readable `response_too_large` error.

## Statistics payloads (API 6)

All timestamps are epoch milliseconds and all durations are milliseconds:
`firstPlayedAtMs`, `lastPlayedAtMs`, `dateAddedMs`, `playedMillis`, `dayStartMs`, `dayEndMs`, and
`sampledAtElapsedRealtimeMs` (monotonic since boot). Dates also carry an ISO
`date`/`dateAddedIso`; range boundaries carry ISO `rangeStart`/`rangeEnd` with
an explicit `zoneOffset`.

Ranges: `today`, `7d`, `30d`, `month`, `90d`, `365d`, `year`, `3y`, and
`all`. `range=all` on `stats_summary` and `stats_top_tracks` is lifetime;
`stats_daily` reports `effectiveRange: "3y"` because daily rows are retained for
1095 days.

`plays` counts threshold crossings: accumulated listening must reach
`min(30s, duration/2)`, with a 1s floor. `touchedTracks` counts every track with
a row in the range, including a zero-length skip. `listenedTracks` counts
`playedMillis > 0` (or a counted play), and `countedTracks` counts at least one
counted play. `startedCount`, `completedCount`, `skipCount`, and
`completedMillis` are lifecycle counters. A manual transition before the end
counts as a skip; a manual transition within the completion tolerance (one second
for normal-length tracks) counts as completed. The player's own end signal is
authoritative: it counts as completed even at non-1x speed or when metadata has no
duration, and it closes the last track without waiting for another transition. A
seek that lands at (or within one second of) the end of the track also triggers
the player's end signal, but that end is not authoritative: such a session only
counts as completed when its accumulated listening covers the duration minus the
same tolerance, so dragging the seek bar to the last second of a long track is a
skip. A zero-duration track is completed only when the player reports that it
ended. Pausing or service shutdown does not close the lifecycle, so a resumed
session is not a skip. Restoring the playback service (process restart with a
saved queue) does not open a statistics session by itself: `startedCount` is only
incremented when playback actually starts on the restored item. Legacy rows
repaired by the
5 -> 6 and 6 -> 7 migrations raise `startedCount` to its provable lower bound:
a counted play, completed/skipped session, or listening timestamp proves at least
one start. Skip/completed counters stay zero for data that never recorded them.
Restoring an older backup applies the same lower-bound repair.

`stats_top_tracks` and `stats_tracks` accept `range` or `day` (mutually
exclusive), `sort` (`plays` or `playedMillis`), `limit`, and `offset`. Range mode
echoes `requestedRange` and `effectiveRange`; day mode returns `range: null`,
`day: "YYYY-MM-DD"`, and `requestedRange`/`effectiveRange` set to `day`. Their
response includes `rangeTotals`, `returned`, `hasMore`, `nextOffset`, and
`truncatedBy` (`limit`, `budget`, or `window`). `nextOffset` is null when
`hasMore` is false. At the bounded read window `hasMore` is false because no
further offset can be served, but `truncatedBy` remains `window` while tracks
exist beyond it. Never sum a page to infer a range total; use `rangeTotals`.

`stats_daily` returns per-day `touchedTracks`, `listenedTracks`,
`countedTracks`, lifecycle counters, and `dayStartMs`/`dayEndMs` with
`utcOffsetMinutes`. Boundaries are derived from the response zone for days whose
legacy rows never captured them, so every day has a non-null value. For legacy
days the earliest known `firstPlayedAtMs` is the last observed instant; rows with
no listening time keep it null. `libraryRevision` in `phocid_capabilities` is a
durable content revision, not the process-local index generation, so it never
resets when the app restarts.

## Sort keys

`library_search_tracks` and `library_get_track` return both `hasSortKey`
and the current `sortKey` value. `library_search_tracks` also accepts a
`hasSortKey` boolean filter.

`sort` accepts three title modes:

- `title`: `sortKey ?: rawTitle`; matches the app UI.
- `rawTitle`: raw title only.
- `sortKey`: keyed tracks by `sortKey`; tracks without a key are placed last,
  ordered by raw title. `descending` reverses only the keyed part; nulls stay
  last.

Set or clear values with `library_update_sort_keys`:

```json
{
  "items": [
    { "libraryId": 123, "sortKey": "example" }
  ],
  "overwrite": false
}
```

- `items` may contain more than 200 entries; only the first 200 are processed.
- The response reports `truncated` and `remaining` when entries are left over.
- `sortKey: null` clears the existing value only when `overwrite` is true.
- `overwrite` defaults to false; existing values are left untouched, including
  attempts to clear them with `sortKey: null`.
- `skippedItems` explains why a valid entry was skipped.
- `library_validate_sort_keys` accepts the same request and returns the same
  counts without writing anything.
- The update tool is hidden unless **Allow agent writes** is enabled. The
  validate tool only requires a compatible main app.
- Only the local `sort_keys` table is written; audio files, tags, and statistics
  are never modified.

## Build

```bash
./gradlew :agent-bridge:assembleRelease
```

The release build uses the same signing config as the main app. Both APKs must
be signed with the same certificate before the Binder signature permission can
work.

The build embeds the app version, UTC build time, and Git commit. CI supplies
`PHOCID_BUILD_COMMIT` and `PHOCID_BUILD_TIME`; local builds fall back to Git
and the current UTC time. The About card displays all three values.

## Use

1. Install `agent-bridge-release.apk`.
2. Open **Phocid Agent Bridge**. The default listen address is
   `127.0.0.1:8765`; change **Listen address** and **Port** if needed.
3. Tap **Start server**. On Android 17+, a non-loopback listen address asks for
   the **local network access** permission first; without it other devices
   cannot connect. **Allow agent writes** is off by default; enabling it exposes
   only `library_update_sort_keys`. Changing settings while running uses
   **Apply & restart**, which disconnects current MCP sessions.
4. In OpenMinis, add an MCP server:

   - Name: `Phocid`
   - Type: `HTTP`
   - URL: the endpoint shown in the app, for example
     `http://127.0.0.1:8765/mcp`
   - Header: `Authorization: Bearer <token>`
   - Authorization: `None`
   - Note: `Read-only by default; optional local sort keys.`

5. Keep the bridge foreground service running while the agent uses it.
6. If the bridge is reported as "Optimized", open **Battery settings** and set
   it to unrestricted. Some OEM ROMs freeze foreground services anyway; the
   heartbeat fields below make that visible after the fact.

The **About** card shows the app version, build time, and short commit. The
same version is reported by MCP `initialize` (`serverInfo.version`) and by
`/health`.

### Listen address notes

- `127.0.0.1` (default): reachable only from the device.
- `0.0.0.0`: all IPv4 interfaces. Other devices on the network can reach the
  bridge; use the phone's LAN IP in the client, not `0.0.0.0`.
- IPv6 literals such as `::1` and `::` are accepted. The app formats IPv6
  URLs with brackets.
- Only IPv4/IPv6 literals are accepted. `localhost` is normalized to
  `127.0.0.1`; host names and URLs are rejected.
- Ports must be in `1024-65535`. The default is `8765`.
- On Android 17+ the local network access permission is required before other
  devices can reach a non-loopback listen address (`0.0.0.0`, `::`, or a LAN
  IP). Denying it leaves the server stopped rather than listening unreachably.
- Loopback (`127.0.0.1`, `::1`, `localhost`) and
  `adb forward tcp:8765 tcp:8765` need no local network permission; USB
  debugging keeps working.

## Debug with curl

These examples use the default port `8765`. If you changed the port, replace
it here. When the bridge is bound to a non-loopback address, `/health` also
needs the bearer token.

```bash
adb forward tcp:8765 tcp:8765
curl -s http://127.0.0.1:8765/health

# Only needed when the bridge is bound to a non-loopback address:
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:8765/health

curl -s \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' \
  http://127.0.0.1:8765/mcp
```

The response should include `serverInfo.name = phocid-agent-bridge`,
`serverInfo.version` matching the app's About card, and a `Mcp-Session-Id`
response header.

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

- The default bind is `127.0.0.1`, which is reachable only from the device.
- Binding to `0.0.0.0`, `::`, or a LAN IP exposes the server to the network.
  Only do this on a trusted network.
- Every `/mcp` request requires the bearer token. `/health` also requires it
  when the server is bound to a non-loopback address.
- The token is stored in the bridge's private SharedPreferences.
- There is no TLS. The bearer token is sent in cleartext over HTTP; avoid
  untrusted networks and untrusted proxies.
- The bridge does not make outbound network requests.
- The bridge is a separate UID; the main APK still has no `INTERNET` permission.
- Sort-key writes are off by default. When enabled, only the local `sort_keys`
  table is written; audio files, tags, and statistics are untouched.

## API version

The main app currently reports `apiVersion: 6`. Each call probes capabilities on
the current Binder session and rejects `library_list_facets` (API 2),
`player_state` (API 3), `library_update_sort_keys` (API 4), and `stats_tracks`
(API 6) with `unsupported_api` when an older main APK is installed. The write
tool is also hidden unless **Allow agent writes** is on.

`tools/list` describes the negotiated contract. With API 6 it advertises
`trackRef`, `dateAddedMs`, explicit units, `rangeTotals`, `hasMore`, `day`,
lifecycle counters, and `stats_tracks`. With API 5 it advertises search
summaries and byte budgets; with API 4 or older it describes the legacy
full-payload search and detail calls.

## Out of scope

Playback control, seek, queue mutation, playlist editing, lyrics, and file
paths are intentionally not exposed.
