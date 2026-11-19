package org.sunsetware.phocid

import android.icu.text.Collator
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
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

        // The first round above threw inside the collector (PLAYLISTS tab settings were
        // missing). If the failure had completed the collector, the job would be inactive and
        // every later playlist or index update would be ignored forever.
        val collector = shortcutJob()
        assertThat(collector.isActive).isTrue()

        // Fixing the preferences must still be processed by that collector.
        preferences.value = Preferences()
        assertThat(collector.isActive).isTrue()
    }

    private fun shortcutJob(): Job {
        val field = PlaylistManager::class.java.getDeclaredField("shortcutJob")
        field.isAccessible = true
        return field.get(manager) as Job
    }
}