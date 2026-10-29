# Playback position clock

Status: deferred design note. Nothing in this document is implemented.

## Context

`827a880` stopped UI progress loops from running while the app was paused or in the
background. On the player screen the playing branch now uses `withFrameNanos`, while the
paused branch polls once per second. That is good for battery life, but the player page has a
second frame-driven workload: dragging the artwork carousel.

The perceived "less smooth when swiping the artwork" has not been proven to come from the
position loop. `TrackCarousel` was not changed by `827a880`. This note records the larger
architecture change that would make the trade-off explicit, not a confirmed bug fix.

## Current position consumers

- `PlayerScreenControls`: while playing, updates `progress` once per frame; while paused,
  once per second.
- `LibraryScreen` mini player: same pattern while visible and resumed.
- `PlayerScreenLyricsView` and `PlayerScreenLyricsOverlay`: poll at 42 ms (about 24 fps)
  while playing and lyrics are visible.
- `PlayerManager.currentPosition` is a direct `MediaController.currentPosition` call.

`ProgressSliderTrack` draws a wave whose phase advances inside the draw block using
`SystemClock.uptimeMillis()`. The wave only advances when the canvas is redrawn, and the
current implementation relies on `progress` state changes to trigger those redraws. Dropping
position updates to 10 Hz would therefore also drop the wave to roughly 10 fps.

## Proposed architecture

Introduce a single playback clock owned by `PlayerManager` (or a small
`PlaybackClock` class it owns):

- Keep a timestamped snapshot: position, duration, isPlaying, speed, and the time at which the
  position was sampled.
- Expose either `positionAt(now)` or a low-frequency `StateFlow` for consumers that only need
  coarse progress.
- Update the snapshot immediately on seek, pause/resume, track transition, and speed change.
- Read `MediaController` on its application looper.
- Start/stop the ticker based on active subscribers (`WhileSubscribed`) or a lifecycle signal,
  so there is no background polling when no screen is watching.

Consumers can then choose their own cadence:

- Progress controls: 10-20 Hz is enough for a duration bar.
- Lyrics: keep 24 fps.
- Wave animation: keep its own frame-clock invalidation, independent of position sampling.
- During a carousel drag, lower-priority animation (including the wave) may be paused or
  deprioritized.

This is the "most correct" direction, but it is an architecture change across data and UI. It
should not be mixed with an unrelated bug fix.

## Smaller alternatives

1. Pause progress updates while the artwork carousel is being dragged. Small change; the wave
   freezes briefly during the gesture.
2. Keep `withFrameNanos` for position, but move the wave to its own frame-clock invalidation so
   position can be sampled less often. Medium change, mostly in `ProgressSlider.kt`.
3. Unified playback clock. Largest change; best long-term consistency.

## Acceptance criteria for the unified clock

- All screens show the same position after seek, pause, resume, track change, and speed change.
- Lyrics stay at 24 fps; the wave remains smooth at 60/120 Hz.
- No position polling continues while the app is paused, backgrounded, or no screen is
  subscribed.
- Dragging the artwork carousel does not trigger a full controls recomposition on every frame.
- Existing playback, statistics, notification, widget, and agent-bridge behavior is unchanged.

## Decision

Deferred. Do not start this migration as part of a routine UI or battery fix. Revisit it if
playback position logic needs to be shared by more consumers, or if the artwork swipe
smoothness issue is confirmed to be caused by the position loop.
