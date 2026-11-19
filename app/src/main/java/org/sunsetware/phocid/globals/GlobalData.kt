package org.sunsetware.phocid.globals

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.PlayerState
import org.sunsetware.phocid.data.PlayerTransientState
import org.sunsetware.phocid.data.PlaylistManager
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.sortkey.SortKeyManager
import org.sunsetware.phocid.stats.StatsManager

/**
 * These are meant for sharing data between contexts. End consumers should not read these directly!
 *
 * Initialized and saved by [org.sunsetware.phocid.MainApplication].
 */
object GlobalData {
    val initialized = AtomicBoolean(false)
    /**
     * Set when the initialization coroutine failed, so consumers can fail fast instead of spinning.
     */
    val initializationFailed = AtomicBoolean(false)

    /**
     * Waits (briefly) for initialization, but never forever. Returns false if initialization failed
     * or did not complete within [timeoutMillis]. Callers must handle the false case.
     *
     * The deadline counts on [SystemClock.elapsedRealtime]: the wall clock can jump backwards (NTP
     * resync, manual time change), which would make a wall-clock deadline wait far longer than the
     * intended bound.
     */
    fun awaitInitialized(timeoutMillis: Long = 10_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!initialized.get()) {
            if (initializationFailed.get()) return false
            if (SystemClock.elapsedRealtime() > deadline) return false
            Thread.sleep(16)
        }
        return true
    }

    @Volatile lateinit var preferences: MutableStateFlow<Preferences>
    @Volatile lateinit var unfilteredTrackIndex: MutableStateFlow<UnfilteredTrackIndex>
    @Volatile lateinit var playerState: MutableStateFlow<PlayerState>

    val playerTransientState = MutableStateFlow(PlayerTransientState())

    @Volatile lateinit var libraryIndex: StateFlow<LibraryIndex>

    @Volatile lateinit var playlistManager: PlaylistManager

    @Volatile lateinit var statsManager: StatsManager

    @Volatile lateinit var sortKeyManager: SortKeyManager
}
