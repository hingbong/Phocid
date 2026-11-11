# Playback position clock

Status: implemented on `main`. The previous deferred design note was superseded and its branch
deleted; this file records the shipped design, its rationale and what is still open.

Commits: `f582d5e` (clock), `5548bef` (controls + mini player), `f15b0d6` (wave), `f3a1a70`
(stale-replay fix), followed by the unit tests and the main-thread guard.

## Why

Four UI consumers queried `MediaController.currentPosition` once per frame while playing — the
player controls, the mini player and the two lyrics views. Every query is a binder round trip to
`PlaybackService`, and the progress bar's wave animation could only advance when `progress`
changed, so its smoothness was tied to the position cadence.

## Architecture

`data/PlaybackClock.kt` keeps one timestamped snapshot (`positionMs`, `durationMs`,
`isPlaying`, `speed`, `sampledAtUptimeMs`) published through a `@Volatile` immutable field.

- `position: StateFlow<Long>` — one coroutine samples the controller and publishes at 10 Hz while
  playing and 1 Hz while paused. It runs only while there is a subscriber
  (`SharingStarted.WhileSubscribed(5_000)`), so nothing polls position while no screen is watching.
- `positionAt(nowUptimeMs)` — pure in-memory extrapolation of the snapshot, clamped to
  `durationMs` when the duration is known. Any thread, no binder.
- `invalidate()` — re-samples immediately. Called from the controller listener's `onEvents` and
  from every seek/play/track/speed mutation, so external changes (notification, headset, track
  transition) reach the clock at once instead of on the next tick.

`PlayerManager` owns the clock and gives it its own
`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, cancelled in `close()`. The
`sample` lambda asserts it runs on the main looper, because that is the only thread allowed to
read the controller; the guard lives in `PlayerManager` so `PlaybackClock` stays pure and
JVM-testable.

`ProgressSlider` drives the wave from its own `withFrameNanos` loop, gated on `animate`, and
reads the frame time inside the draw lambda. The wave therefore runs at the display refresh rate no
matter how often the position changes, and the phase is re-anchored when the animation restarts.

## Cadence

| Consumer | Cadence | Source |
|---|---|---|
| Controls progress | 10 Hz | `PlaybackClock.position` |
| Mini player | 10 Hz | `PlaybackClock.position` |
| Lyrics | frame loop | `PlaybackClock.positionAt()` |
| Wave | 60/120 Hz | own `withFrameNanos` |
| Notification / widget | event-driven | `PlaybackStateCompat` (unchanged) |
| Agent / appfunctions | one-shot | own per-query controller (unchanged) |

## Deliberate non-changes

- `AgentQueryService` and `PhocidAppFunctionDataSource` build and release their own controller
  per query. They must not depend on the UI-side clock: it may not exist while backgrounded, and
  its ticker is stopped with no subscribers, so it would return a stale position exactly when an
  agent needs a fresh one.
- `PlayerState` (serialized and persisted) never receives the clock position, and the clock never
  bumps `transientState.version`; doing so would force a full-page recomposition at 10 Hz.
- `PlayerManager.currentPosition` remains a direct controller read for the seek decisions in
  `seekToPreviousSmart` and `play`'s restart check.
- `SystemClock.uptimeMillis()` is used rather than `elapsedRealtime()`: sample, extrapolation and
  frame times share one timebase. Deep sleep freezes the value, which is harmless — the ticker is
  stopped when unsubscribed and `invalidate()` re-samples on resume.

## Tests

`app/src/test/java/org/sunsetware/phocid/PlaybackClockTest.kt` covers the extrapolation rules as
pure JVM tests: no sample, paused (frozen, negative clamped), playing with speed 1 and 2, clamp to
duration, unknown duration, stale timestamp, and `invalidate()` taking a new base immediately.

## Still open

Manual verification on a device/emulator (not covered by unit tests): seek by drag and by button,
pause, resume, track change, speed change; wave smoothness while playing and freeze while paused;
carousel drag smoothness; slider drag priority; ticker stop when backgrounded and no stale
previous-track flash on return; buffering; notification/headset transitions; and a regression pass
over statistics, notification, widget, agent bridge and appfunctions.
