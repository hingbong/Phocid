@file:OptIn(UnstableApi::class)

package org.sunsetware.phocid.data

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import org.sunsetware.phocid.FILE_PATH_KEY
import org.sunsetware.phocid.ROOT_MEDIA_ID
import org.sunsetware.phocid.UNSHUFFLED_INDEX_KEY
import org.sunsetware.phocid.URI_KEY
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.ui.views.library.LibraryScreenTabType
import org.sunsetware.phocid.utils.Random

@Serializable
@Immutable
data class PlayerState(
    /** To restore the unshuffled play queue: `(0..<length).map { actualPlayQueue[it] }` */
    val unshuffledPlayQueueMapping: List<Int>? = null,
    val actualPlayQueue: List<Long> = emptyList(),
    val currentIndex: Int = 0,
    val currentPosition: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
)

@Immutable data class PlayerTransientState(val version: Long = -1, val isPlaying: Boolean = false)

/** This method should work even if values of [UNSHUFFLED_INDEX_KEY] are discontinuous. */
fun Player.capturePlayerState(): PlayerState {
    val mediaItems = (0..<mediaItemCount).map { getMediaItemAt(it) }
    fun getUnshuffledPlayQueueMapping(): List<Int> {
        return mediaItems
            .mapIndexedNotNull { index, mediaItem ->
                mediaItem.getUnshuffledIndex()?.let { Pair(index, it) }
            }
            .sortedBy { it.second }
            .map { it.first }
    }
    val actualPlayQueue = mediaItems.map { it.mediaId.toLong() }
    // Position 0 while playing is deliberate: the live position is derived from the
    // PlaybackClock by extrapolation, and restoring must never auto-resume playback. A
    // restored session starts paused at the captured boundary, so a stale mid-track value
    // would be misleading; 0 plus the "is playing" flag keeps restore semantics simple.
    return PlayerState(
        if (shuffleModeEnabled) getUnshuffledPlayQueueMapping() else null,
        actualPlayQueue,
        currentMediaItemIndex,
        if (isPlaying) 0 else currentPosition,
        shuffleModeEnabled,
        repeatMode,
        playbackParameters.speed,
        playbackParameters.pitch,
    )
}

/**
 * Maps the captured play-queue position onto the restored queue.
 *
 * Ids dropped from [UnfilteredTrackIndex] (their files disappeared since the state was captured)
 * shift every later item, so the captured index cannot be reused directly: prefer the captured
 * item itself, then the first surviving item after it (what would have played next), then the
 * nearest one before it. The second component is true only when the captured item itself
 * survived, in which case its captured position is still meaningful.
 */
internal fun computeRestoredSeek(
    survivingOriginalIndexes: List<Int>,
    capturedIndex: Int,
): Pair<Int, Boolean> {
    val captured = survivingOriginalIndexes.indexOf(capturedIndex)
    if (captured >= 0) {
        return captured to true
    }
    val next = survivingOriginalIndexes.indexOfFirst { it > capturedIndex }
    if (next >= 0) {
        return next to false
    }
    val previous = survivingOriginalIndexes.indexOfLast { it < capturedIndex }
    if (previous >= 0) {
        return previous to false
    }
    return 0 to false
}

fun Player.restorePlayerState(state: PlayerState, unfilteredTrackIndex: UnfilteredTrackIndex) {
    // Shuffle must be set before items or items will be shuffled again
    shuffleModeEnabled = state.shuffle
    val survivingIndexes = mutableListOf<Int>()
    // The queue can hold thousands of items: map each current index to its position in the
    // unshuffled order once instead of an O(n) indexOf scan per item. Values are unique (the
    // mapping is a permutation), so first-wins lookup reproduces indexOf exactly.
    val unshuffledPositionOf =
        state.unshuffledPlayQueueMapping?.let { mapping ->
            val inverse = HashMap<Int, Int>(mapping.size * 2)
            mapping.forEachIndexed { position, currentIndex ->
                if (currentIndex !in inverse) inverse[currentIndex] = position
            }
            inverse
        }
    setMediaItems(
        state.actualPlayQueue.mapIndexedNotNull { index, id ->
            unfilteredTrackIndex.tracks[id]?.getMediaItem(
                unshuffledPositionOf?.getOrDefault(index, -1)
            )?.also { survivingIndexes += index }
        }
    )
    // Dropped tracks shift the queue; seek to where the captured item (or its nearest
    // survivor) landed instead of trusting the captured index blindly.
    val (seekIndex, capturedItemSurvived) =
        computeRestoredSeek(survivingIndexes, state.currentIndex)
    seekTo(seekIndex, if (capturedItemSurvived) state.currentPosition else 0)
    repeatMode = state.repeat
    playbackParameters = PlaybackParameters(state.speed, state.pitch)
}

private val transientStateVersion = AtomicLong(0)

fun Player.captureTransientState(): PlayerTransientState {
    return PlayerTransientState(
        transientStateVersion.getAndIncrement(),
        playWhenReady && playbackState != Player.STATE_ENDED,
    )
}

fun transformOnSetTracks(
    shuffle: Boolean,
    mediaItems: List<MediaItem>,
    index: Int?,
): Pair<List<MediaItem>, Int> {
    if (!shuffle) {
        return mediaItems.map { it.setUnshuffledIndex(null) } to (index ?: 0)
    } else {
        val shuffledIndices =
            if (index != null) {
                listOf(index) + mediaItems.indices.filter { it != index }.shuffled(Random)
            } else {
                mediaItems.indices.shuffled(Random)
            }
        return shuffledIndices.map { i -> mediaItems[i].setUnshuffledIndex(i) } to 0
    }
}

fun transformOnAddTracks(state: PlayerState, mediaItems: List<MediaItem>): List<MediaItem> {
    val firstIndex = state.actualPlayQueue.size
    return mediaItems.mapIndexed { i, mediaItem ->
        mediaItem.setUnshuffledIndex(if (!state.shuffle) null else firstIndex + i)
    }
}

fun transformMediaSessionCallbackItems(
    preferences: Preferences,
    libraryIndex: LibraryIndex,
    playlists: Map<UUID, RealizedPlaylist>,
    /** These only contain valid [MediaItem.mediaId]s; all other fields are null */
    mediaItems: List<MediaItem>,
): List<MediaItem> {
    var items = mediaItems.mapNotNull {
        val trackId = it.mediaId.toLongOrNull()
        if (trackId != null) libraryIndex.tracks[it.mediaId.toLongOrNull()]?.getMediaItem(null)
        else it
    }
    while (items.any { it.mediaId.firstOrNull()?.isDigit() == false }) {
        items = items.flatMap {
            if (it.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_MUSIC) {
                getChildMediaItems(preferences, libraryIndex, playlists, it.mediaId) ?: emptyList()
            } else {
                listOf(it)
            }
        }
    }

    return items
}

fun Track.getMediaItem(unshuffledIndex: Int?): MediaItem {
    val unshuffledMediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setTitle(displayTitle)
                    .setArtist(displayArtist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(displayAlbumArtist)
                    .setGenre(displayGenre)
                    .setRecordingYear(year)
                    .setTrackNumber(trackNumber)
                    .setDiscNumber(discNumber)
                    .setExtras(bundleOf(URI_KEY to uri.toString(), FILE_PATH_KEY to path))
                    .build()
            )
            .build()

    return if (unshuffledIndex == null) unshuffledMediaItem
    else unshuffledMediaItem.setUnshuffledIndex(unshuffledIndex)
}

private val tabLookup = LibraryScreenTabType.entries.associateBy { it.mediaId }

/**
 * All non-track [MediaItem.mediaId]s are in the format of `type:path`. Types are defined in
 * [LibraryScreenTabType.mediaId]).
 */
fun getChildMediaItems(
    preferences: Preferences,
    libraryIndex: LibraryIndex,
    playlists: Map<UUID, RealizedPlaylist>,
    parentId: String,
): List<MediaItem>? {
    val segments = parentId.split(':', limit = 2)
    val type = tabLookup[segments[0]]
    val path = segments.getOrNull(1)
    when {
        parentId == ROOT_MEDIA_ID ->
            return preferences.tabs.map {
                MediaItem.Builder()
                    .setMediaId(it.type.mediaId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setMediaType(it.type.mediaType)
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setTitle(Strings[it.type.stringId])
                            .build()
                    )
                    .build()
            }
        type != null -> {
            val tabSettings = preferences.tabSettings[type]!!
            val collectionSettings = preferences.collectionViewSorting[type.collectionType]
            val collectionSortingKeys =
                type.collectionType?.sortingOptions[collectionSettings?.first]?.keys ?: emptyList()
            val collectionSortAscending = collectionSettings?.second != false
            when (type) {
                LibraryScreenTabType.TRACKS -> {
                    return libraryIndex.tracks.values
                        .sorted(
                            preferences.sortCollator,
                            tabSettings.sortingKeys,
                            tabSettings.sortAscending,
                        )
                        .map { it.getMediaItem(null) }
                }
                LibraryScreenTabType.ALBUMS -> {
                    return if (path == null)
                        libraryIndex.albums.values
                            .sorted(
                                preferences.sortCollator,
                                tabSettings.sortingKeys,
                                tabSettings.sortAscending,
                            )
                            .map {
                                MediaItem.Builder()
                                    .setMediaId("${type.mediaId}:${it.albumKey}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setTitle(it.name)
                                            .setSubtitle(it.displayAlbumArtist)
                                            .build()
                                    )
                                    .build()
                            }
                    else
                        libraryIndex.albums[AlbumKey(path)]
                            ?.tracks
                            ?.sorted(
                                preferences.sortCollator,
                                collectionSortingKeys,
                                collectionSortAscending,
                            )
                            ?.map { it.getMediaItem(null) } ?: emptyList()
                }
                LibraryScreenTabType.ARTISTS -> {
                    return if (path == null)
                        libraryIndex.artists.values
                            .sorted(
                                preferences.sortCollator,
                                tabSettings.sortingKeys,
                                tabSettings.sortAscending,
                            )
                            .map {
                                MediaItem.Builder()
                                    .setMediaId("${type.mediaId}:${it.name}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setTitle(it.name)
                                            .build()
                                    )
                                    .build()
                            }
                    else
                        libraryIndex.artists[path]
                            ?.tracks
                            ?.sorted(
                                preferences.sortCollator,
                                collectionSortingKeys,
                                collectionSortAscending,
                            )
                            ?.map { it.getMediaItem(null) } ?: emptyList()
                }
                LibraryScreenTabType.ALBUM_ARTISTS -> {
                    return if (path == null)
                        libraryIndex.albumArtists.values
                            .sorted(
                                preferences.sortCollator,
                                tabSettings.sortingKeys,
                                tabSettings.sortAscending,
                            )
                            .map {
                                MediaItem.Builder()
                                    .setMediaId("${type.mediaId}:${it.name}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setTitle(it.name)
                                            .build()
                                    )
                                    .build()
                            }
                    else
                        libraryIndex.albumArtists[path]
                            ?.tracks
                            ?.sorted(
                                preferences.sortCollator,
                                collectionSortingKeys,
                                collectionSortAscending,
                            )
                            ?.map { it.getMediaItem(null) } ?: emptyList()
                }
                LibraryScreenTabType.GENRES -> {
                    return if (path == null)
                        libraryIndex.genres.values
                            .sorted(
                                preferences.sortCollator,
                                tabSettings.sortingKeys,
                                tabSettings.sortAscending,
                            )
                            .map {
                                MediaItem.Builder()
                                    .setMediaId("${type.mediaId}:${it.name}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_GENRE)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setTitle(it.name)
                                            .build()
                                    )
                                    .build()
                            }
                    else
                        libraryIndex.genres[path]
                            ?.tracks
                            ?.sorted(
                                preferences.sortCollator,
                                collectionSortingKeys,
                                collectionSortAscending,
                            )
                            ?.map { it.getMediaItem(null) } ?: emptyList()
                }
                LibraryScreenTabType.PLAYLISTS -> {
                    return if (path == null)
                        playlists
                            .asIterable()
                            .sortedBy(
                                preferences.sortCollator,
                                tabSettings.sortingKeys,
                                tabSettings.sortAscending,
                            ) {
                                it.value
                            }
                            .map { (key, value) ->
                                MediaItem.Builder()
                                    .setMediaId("${type.mediaId}:${key}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setTitle(value.displayName)
                                            .build()
                                    )
                                    .build()
                            }
                    else
                        playlists[
                                try {
                                    UUID.fromString(path)
                                } catch (_: Exception) {
                                    null
                                }]
                            ?.entries
                            // Entries whose file is gone have a null track; drop them before
                            // the sort instead of crashing the whole browse. The library UI
                            // list filters them the same way.
                            ?.filter { it.track != null }
                            ?.sortedBy(
                                preferences.sortCollator,
                                collectionSortingKeys,
                                collectionSortAscending,
                            ) {
                                it.track!!
                            }
                            ?.mapNotNull { it.track?.getMediaItem(null) } ?: emptyList()
                }
                LibraryScreenTabType.FOLDERS -> {
                    val folder =
                        libraryIndex.folders[
                                path ?: preferences.folderTabRoot ?: libraryIndex.defaultRootFolder]
                    if (folder == null) return emptyList()
                    return folder.childFolders
                        .mapNotNull { libraryIndex.folders[it] }
                        .plus(folder.childTracks)
                        .let {
                            if (path == null)
                                it.sorted(
                                    preferences.sortCollator,
                                    tabSettings.sortingKeys,
                                    tabSettings.sortAscending,
                                )
                            else
                                it.sorted(
                                    preferences.sortCollator,
                                    collectionSortingKeys,
                                    collectionSortAscending,
                                )
                        }
                        .map {
                            if (it is Folder) {
                                MediaItem.Builder()
                                    .setMediaId("${type.mediaId}:${it.path}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setTitle(it.fileName)
                                            .build()
                                    )
                                    .build()
                            } else if (it is Track) {
                                it.getMediaItem(null)
                            } else {
                                throw Error()
                            }
                        }
                }
            }
        }
        else -> return null
    }
}

fun MediaItem.getUnshuffledIndex(): Int? {
    return mediaMetadata.extras?.getInt(UNSHUFFLED_INDEX_KEY, -1)?.takeIf { it >= 0 }
}

fun MediaItem.setUnshuffledIndex(unshuffledIndex: Int?): MediaItem {
    return buildUpon()
        .setMediaMetadata(
            mediaMetadata
                .buildUpon()
                .setExtras(
                    (mediaMetadata.extras?.clone() as Bundle? ?: bundleOf()).apply {
                        putInt(UNSHUFFLED_INDEX_KEY, unshuffledIndex ?: -1)
                    }
                )
                .build()
        )
        .build()
}

@Immutable
@Serializable
data class PlayerTimerSettings(
    val duration: Duration = 10.minutes,
    val finishLastTrack: Boolean = true,
)
