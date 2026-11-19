package org.sunsetware.phocid

import android.icu.text.Collator
import androidx.core.content.pm.ShortcutManagerCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.PlaylistManager
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.SpecialPlaylist
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.ui.views.library.LibraryScreenTabInfo
import org.sunsetware.phocid.ui.views.library.LibraryScreenTabType

@RunWith(RobolectricTestRunner::class)
class PlaylistShortcutsRecoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var manager: PlaylistManager

    @After
    fun tearDown() {
        if (::manager.isInitialized) manager.close()
        scope.cancel()
    }

    @Test
    fun shortcutCollectorSurvivesAFailingUpdateAndResumes() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val collator = Collator.getInstance(Locale.ROOT).freeze()
        // Broken on purpose: the PLAYLISTS tab entry is missing, so the first shortcut update
        // throws inside the collector. The old pipeline caught the exception once and completed,
        // freezing playlist shortcuts for the rest of the process lifetime.
        val preferences =
            MutableStateFlow(
                Preferences(
                    tabSettings =
                        mapOf(
                            LibraryScreenTabType.TRACKS to
                                LibraryScreenTabInfo(type = LibraryScreenTabType.TRACKS)
                        )
                )
            )
        val libraryIndex =
            MutableStateFlow(
                LibraryIndex(
                    UnfilteredTrackIndex(null, emptyMap()),
                    collator,
                    emptyList(),
                    emptyList(),
                )
            )

        manager = PlaylistManager(context, scope, preferences, libraryIndex)
        manager.initialize()

        // Fixing the preferences must produce a working shortcut update, proving the collector
        // survived the earlier failure instead of silently dying.
        preferences.value = Preferences()

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertThat(shortcuts.map { it.id })
            .contains("playlist:${SpecialPlaylist.FAVORITES.key}")
    }
}
