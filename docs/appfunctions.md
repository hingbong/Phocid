# AppFunctions

This document records the AppFunctions experiment in the main APK: what is exposed, how it maps
to the existing agent API, and what still blocks end-to-end use.

## Status

- Library: `androidx.appfunctions:appfunctions:1.0.0-rc01` (Google Maven lastUpdated
  2026-11-04, newest published version). The runtime and compiler artifacts were verified on
  Google Maven; the matching compiler is `appfunctions-compiler:1.0.0-rc01`.
- Platform: AppFunctions is still an experimental preview. It only works on Android 16 (API 36)
  and newer, and during the preview only allowlisted callers (Gemini private preview, EAP apps)
  can discover or execute the functions.
- Main APK: `minSdk` stays 35. On 35 the service is simply never bound; the generated class and
  the manifest entry are inert.
- No `INTERNET` permission is added. AppFunctions executes in-process, so "main APK has no
  network" still holds.

## Exposed functions

| Function | Parameters | Returns | Notes |
| --- | --- | --- | --- |
| `searchTracks` | `query: String`, `limit: Int = 20` | `List<TrackSummary>` | Case-insensitive free text over title, file name, artists, album, album artists, genres. Every whitespace-separated word must match. At most 50 results. |
| `getNowPlaying` | none | `NowPlayingState` | Track, artist, album, position, queue length, shuffle/repeat, playback state. `active = false` when the playback service is not running. |
| `getLibraryStats` | none | `LibraryStats` | Track, artist, album, album-artist, genre counts plus summed duration. |
| `getPlaybackStats` | `range: String? = null` | `PlaybackStats` | Ranges: `today`, `7d`, `30d`, `month`, `90d`, `365d`, `year`, `3y`, `all`; null/blank means `30d`. Reads the Room statistics repository. |
| `setPlayback` | `command: String` | `NowPlayingState` | Commands: `play`, `pause`, `toggle`, `next`, `previous`. Fails when the player is not running or the queue has no next/previous item. |

The KDoc in `BasePhocidAppFunctionService` is the schema description, so it is written for an
agent rather than for a human reading the app. `@AppFunctionSerializable` models live in
`PhocidAppFunctionModels.kt` with inline property KDoc, which KSP requires to extract field
descriptions.

## Mapping from the bridge MCP tools

| Bridge MCP tool | AppFunction | Why |
| --- | --- | --- |
| `library_search_tracks` | `searchTracks` | Highest-value local lookup; same library index the UI uses. The MCP shape (filters, byte budget, pagination) is reduced to free text + limit because agent schemas should stay small. |
| `player_state` | `getNowPlaying` | Same Media3 session read as `AgentQueryService.playerStateJson`, flattened into a serializable snapshot. |
| `phocid_capabilities` (counts only) | `getLibraryStats` | Cheap aggregate answer without exposing the full capability document. |
| `stats_summary` | `getPlaybackStats` | Same `StatsRepository.summary` call and the same range names. |
| (new capability) | `setPlayback` | Mirrors the existing `MediaController` controls used by the UI; the bridge has no equivalent tool yet. |

Deliberately not mapped yet:

- `library_get_track`, `library_list_facets` — add after the first four prove discoverability;
  track details are mostly redundancy once search returns the summary.
- `stats_top_tracks`, `stats_daily`, `stats_tracks` — pagination and byte budgets do not fit
  a single AppFunction response well; the first version only returns totals.
- `library_update_sort_keys` — a write path. It needs an explicit user-confirmation design before
  it is exposed to an agent.
- `resolve_stats_uuid` — only useful for backup/portability, not for an on-device agent.

## Why these five

- They cover what an on-device agent most often asks a music app: find a track, what is playing,
  how big is the library, and how much the user listened.
- They reuse existing application logic instead of adding a parallel query layer:
  `GlobalData.libraryIndex` for search and counts, `StatsManager.repository` for history, and
  the same `MediaController` session that `AgentQueryService` already binds.
- They never return filesystem paths or raw file contents, and none of them writes user data.
- Playback control is included because it is the natural follow-up to `searchTracks` and reuses
  the already-connected controller, so the incremental cost is one method call per command.

## Build integration

`gradle/libs.versions.toml`:

```toml
[versions]
appfunctions = "1.0.0-rc01"

[libraries]
androidx-appfunctions = { module = "androidx.appfunctions:appfunctions", version.ref = "appfunctions" }
androidx-appfunctions-compiler = { module = "androidx.appfunctions:appfunctions-compiler", version.ref = "appfunctions" }
```

`app/build.gradle.kts`:

```kotlin
implementation(libs.androidx.appfunctions)
ksp(libs.androidx.appfunctions.compiler)
```

The existing `ksp(libs.androidx.room.compiler)` stays as-is. KSP runs both processors in the same
task; Room and AppFunctions generate into their own outputs and do not share symbols.

Requirements: `compileSdk` must be 37 or newer for rc01 (the AAR declares
`minCompileSdk=37`; the older setup guide still says 36). The project already uses 37.

## Manifest

```xml
<service
    android:name="org.sunsetware.phocid.appfunctions.PhocidAppFunctionService"
    android:exported="true"
    android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
    tools:targetApi="36">
    <property
        android:name="android.app.appfunctions.schema"
        android:value="app_functions_schema.xsd" />
    <property
        android:name="android.app.appfunctions.v2"
        android:value="phocid_app_functions.xml" />
    <intent-filter>
        <action android:name="android.app.appfunctions.AppFunctionService" />
    </intent-filter>
</service>
```

`PhocidAppFunctionService` is generated by KSP from the
`@AppFunctionServiceEntryPoint(serviceName = ..., appFunctionXmlFileName = ...)` annotation on
`BasePhocidAppFunctionService`. The `.v2` value is the XML file name without the `.xml`
extension. Do not hand-write either the class or the XML asset.

## Missing app-level metadata

The recommended setup also registers `android.app.appfunctions.app_metadata` with
`res/xml/app_metadata.xml`. That file was intentionally left out of this change (the change is
scoped to the Kotlin service, Gradle, and the manifest), so agents currently get per-function KDoc
but no app-wide instructions. Functions are still discoverable; adding the file only improves
orchestration quality. To finish the setup, add:

```xml
<AppFunctionAppMetadata xmlns:appfn="http://schemas.android.com/apk/androidx.appfunctions"
    appfn:instructions="Phocid is an offline, local music player.
    Operational patterns:
    - Use searchTracks to resolve a title, artist, or album before calling setPlayback.
    - Use getNowPlaying for 'what is playing' and position questions.
    - Use getLibraryStats for library-size questions and getPlaybackStats for listening history.
    Constraints:
    - setPlayback only controls the existing queue; it cannot add tracks.
    - Library contents depend on the user's granted media permission." />
```

and register it under `<application>`:

```xml
<property
    android:name="android.app.appfunctions.app_metadata"
    android:resource="@xml/app_metadata" />
```

## Verification

Build and install through CI or a machine with enough memory. On an Android 16+ device:

```bash
adb shell cmd app_function list-app-functions --package org.sunsetware.phocid.next
adb shell "cmd app_function execute-app-function \
  --package org.sunsetware.phocid.next \
  --function 'org.sunsetware.phocid.appfunctions.BasePhocidAppFunctionService#getLibraryStats' \
  --parameters '{}'"
adb shell "cmd app_function execute-app-function \
  --package org.sunsetware.phocid.next \
  --function 'org.sunsetware.phocid.appfunctions.BasePhocidAppFunctionService#searchTracks' \
  --parameters '{"query": "kind of blue", "limit": 5}'"
```

## Risks

- rc01 is a release candidate of an experimental platform feature. API and manifest format can
  still change, and the preview gate means a correct implementation may be undiscoverable.
- No `app_metadata.xml` is registered, so agents receive only per-function KDoc. Orchestration
  quality may suffer until the app-level instructions are added.
- The compiler depends on KSP's `symbol-processing-api` 2.0.10-1.0.24 while the project uses the
  KSP plugin 2.3.12. The old processor API is expected to stay compatible, but this pairing has
  not been compiled locally.
- AGP 9.4.1 with rc01 asset generation is also unverified locally; if the generated
  `assets/phocid_app_functions.xml` is missing, discovery silently returns nothing.
- `getNowPlaying` / `setPlayback` bind a `MediaController` with a 2 s timeout, copying
  `AgentQueryService`. A busy main thread or a slow session service can exceed it and surface as
  an "unknown" AppFunction error.
- Release builds run R8. The library ships keep rules for generated inventories and serializable
  class names, but the generated service must stay reachable from the manifest.
- AppFunctions does not replace the bridge APK yet: the bridge is a remote MCP server over HTTP for
  a desktop agent, while AppFunctions is local-only discovery by allowlisted on-device callers.

## TODO

- Add `res/xml/app_metadata.xml` and its manifest property (see above).
- Run `adb shell cmd app_function list-app-functions` on API 36 hardware and confirm all five
  functions are indexed with descriptions.
- Test the release build with R8 and the KSP-generated asset.
- Consider `getTrack(trackId)`, `listFacets`, and `getTopTracks(range, limit)` once schemas can
  stay within one response budget.
- Consider `playTrack(trackId)` so a search result can be played directly. That needs a deliberate
  queue-replacement rule (shuffle, "play next", or replace) instead of the current transport-only
  `setPlayback`.
- Decide whether `setPlayback` should require an explicit user confirmation for `next` /
  `previous`; the current version mirrors the media notification controls.
- Re-evaluate bridge retirement after AppFunctions reaches GA and Gemini opens the pipeline beyond
  the EAP. Until then keep both: the bridge is the only path for a desktop/remote agent.
