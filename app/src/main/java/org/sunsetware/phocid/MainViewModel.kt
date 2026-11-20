package org.sunsetware.phocid

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.sunsetware.phocid.data.Lyrics
import org.sunsetware.phocid.data.PlayerManager
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.scanParseFingerprint
import org.sunsetware.phocid.data.scanTracks
import org.sunsetware.phocid.globals.GlobalData
import org.sunsetware.phocid.stats.resolver.toTrackMeta
import org.sunsetware.phocid.ui.components.ArtworkCache
import org.sunsetware.phocid.ui.views.library.LibraryScreenTabInfo
import org.sunsetware.phocid.utils.ScanCoalescer
import org.sunsetware.phocid.utils.externalStorageRoots

private const val MEDIA_SCAN_TIMEOUT_MILLIS = 30_000L
private const val LIBRARY_INDEX_SYNC_TIMEOUT_MILLIS = 30_000L

class MainViewModel(private val application: Application) : AndroidViewModel(application) {
    private val _initialized = MutableStateFlow(false)
    val initialized = _initialized.asStateFlow()
    private val initializationStarted = AtomicBoolean(false)
    private val scanCoalescer = ScanCoalescer()

    lateinit var playerManager: PlayerManager
    lateinit var uiManager: UiManager

    val preferences
        get() = GlobalData.preferences.asStateFlow()

    val unfilteredTrackIndex
        get() = GlobalData.unfilteredTrackIndex.asStateFlow()

    val libraryIndex
        get() = GlobalData.libraryIndex

    val playlistManager
        get() = GlobalData.playlistManager

    val statsManager
        get() = GlobalData.statsManager

    val lyricsCache = AtomicReference(null as Pair<Long, Lyrics>?)
    // Three pages are on screen at once and the neighbours are kept warm ahead of a swipe; the
    // extra headroom stops a prefetch from evicting a page that is about to be displayed.
    val carouselArtworkCache = ArtworkCache(viewModelScope, 6)
    val playlistIoDirectory = MutableStateFlow(null as Uri?)
    private val _libraryScanState = MutableStateFlow(null as Boolean?)
    /**
     * - null: not scanning
     * - true: forced (manual)
     * - false: not forced (auto)
     */
    val libraryScanState = _libraryScanState.asStateFlow()
    private val _libraryScanProgress = MutableStateFlow(null as Pair<Int, Int>?)
    val libraryScanProgress = _libraryScanProgress.asStateFlow()

    fun initialize() {
        if (!initializationStarted.getAndSet(true)) {
            viewModelScope.launch {
                while (!GlobalData.initialized.get()) {
                    delay(1)
                }
                playerManager =
                    PlayerManager(GlobalData.playerState, GlobalData.playerTransientState)
                uiManager =
                    UiManager(
                        application.applicationContext,
                        viewModelScope,
                        preferences,
                        libraryIndex,
                        playlistManager,
                    )
                try {
                    playerManager.initialize(application.applicationContext)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // A same-process controller connection only fails pathologically; leave
                    // the loading state in place and let the next launch retry, instead of
                    // crashing the whole app or hanging a coroutine forever.
                    Log.e("Phocid", "Failed to connect to the playback service", ex)
                    return@launch
                }
                _initialized.update { true }
            }
        }
    }

    override fun onCleared() {
        // Initialization may not have run or completed yet; closing an uninitialized
        // lateinit manager would crash during teardown instead.
        if (::playerManager.isInitialized) {
            playerManager.close()
        }
        if (::uiManager.isInitialized) {
            uiManager.close()
        }
        super.onCleared()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun scanLibrary(force: Boolean): Job {
        return viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Coalesce contended scans: a caller that arrives while a scan is in flight
                // waits for that scan and returns instead of queueing another full scan.
                // Forced (manual) scans still run after the in-flight one completes.
                scanCoalescer.scan(force) {
                    Log.d("Phocid", "Library scan started")
                    try {
                        _libraryScanProgress.update { null }
                        _libraryScanState.update { force }

                        if (force || preferences.value.alwaysRescanMediaStore) {
                            val storages =
                                application.applicationContext
                                    .externalStorageRoots()
                                    .map { it.path }
                                    .toTypedArray()
                            // scanFile invokes the callback once per path. Wait for *all* roots,
                            // with an absolute timeout so a missing callback cannot wedge the scan
                            // (and hold the mutex / the RESUMED intent) forever.
                            val remaining = AtomicInteger(storages.size)
                            MediaScannerConnection.scanFile(
                                application.applicationContext,
                                storages,
                                arrayOf("audio/*"),
                            ) { _, _ ->
                                remaining.decrementAndGet()
                            }
                            withTimeoutOrNull(MEDIA_SCAN_TIMEOUT_MILLIS) {
                                while (remaining.get() > 0) {
                                    delay(10)
                                }
                            }
                        }

                        val currentPreferences = preferences.value
                        val newTrackIndex =
                            scanTracks(
                                application.applicationContext,
                                currentPreferences.advancedMetadataExtraction,
                                currentPreferences.disableArtworkColorExtraction,
                                if (force) null else unfilteredTrackIndex.value,
                                currentPreferences.artistMetadataSeparators,
                                currentPreferences.artistMetadataSeparatorExceptions,
                                currentPreferences.genreMetadataSeparators,
                                currentPreferences.genreMetadataSeparatorExceptions,
                                parseFingerprint =
                                    scanParseFingerprint(
                                        currentPreferences.advancedMetadataExtraction,
                                        currentPreferences.disableArtworkColorExtraction,
                                        currentPreferences.artistMetadataSeparators,
                                        currentPreferences.artistMetadataSeparatorExceptions,
                                        currentPreferences.genreMetadataSeparators,
                                        currentPreferences.genreMetadataSeparatorExceptions,
                                    ),
                            ) { current, total ->
                                _libraryScanProgress.update { current to total }
                            }
                        if (newTrackIndex != null) {
                            GlobalData.unfilteredTrackIndex.update { newTrackIndex }
                            // Suspend until the derived LibraryIndex has caught up instead of
                            // spinning on its value. Subscribing here also ensures the cold
                            // combine recomputes even when no UI is currently collecting it.
                            withTimeoutOrNull(LIBRARY_INDEX_SYNC_TIMEOUT_MILLIS) {
                                GlobalData.libraryIndex.first {
                                    it.flowVersion >= newTrackIndex.flowVersion
                                }
                            }
                            Log.d("Phocid", "Library scan completed")
                            // Upgrade legacy basename REL_PATH aliases off the scan critical
                            // section: a detached IO job so hashing never holds the ScanCoalescer
                            // mutex or blocks the scan/RESUMED intent.
                            val scanned = newTrackIndex.tracks.values.map { it.toTrackMeta() }
                            viewModelScope.launch(Dispatchers.IO) {
                                runCatching { statsManager.rebuildRelPathAliases(scanned) }
                                    .onFailure {
                                        Log.e("Phocid", "REL_PATH alias rebuild failed", it)
                                    }
                            }
                        } else {
                            Log.d(
                                "Phocid",
                                "Library scan aborted: MediaStore query failed or permission denied",
                            )
                        }
                        playlistManager.syncPlaylists()
                    } finally {
                        _libraryScanState.update { null }
                    }
                }
            }
        }
    }

    fun updatePreferences(transform: (Preferences) -> Preferences) {
        GlobalData.preferences.update(transform)
    }

    fun updateTabInfo(index: Int, transform: (LibraryScreenTabInfo) -> LibraryScreenTabInfo) {
        GlobalData.preferences.update { preferences ->
            val type = preferences.tabs[index].type
            preferences.copy(
                tabSettings =
                    preferences.tabSettings.mapValues {
                        if (it.key == type) transform(it.value) else it.value
                    }
            )
        }
    }
}
