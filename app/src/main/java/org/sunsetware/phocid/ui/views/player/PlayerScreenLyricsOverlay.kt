package org.sunsetware.phocid.ui.views.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.sunsetware.phocid.data.Lyrics
import org.sunsetware.phocid.data.LyricsDisplayPreference
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.ui.theme.EXIT_DURATION
import org.sunsetware.phocid.ui.theme.INACTIVE_ALPHA
import org.sunsetware.phocid.ui.theme.Typography
import org.sunsetware.phocid.ui.theme.emphasizedExit

@Immutable
sealed class PlayerScreenLyricsOverlay {
    @Composable
    abstract fun Compose(
        lyrics: Lyrics?,
        currentPosition: () -> Long,
        preferences: Preferences,
        containerColor: Color,
        contentColor: Color,
        overlayVisibility: Float,
        isPlaying: Boolean,
    )
}

@Immutable
object PlayerScreenLyricsOverlayDefault : PlayerScreenLyricsOverlay() {
    @Composable
    override fun Compose(
        lyrics: Lyrics?,
        currentPosition: () -> Long,
        preferences: Preferences,
        containerColor: Color,
        contentColor: Color,
        overlayVisibility: Float,
        isPlaying: Boolean,
    ) {
        fun getLineIndex(): Int? {
            return lyrics?.getLineIndex((currentPosition() + EXIT_DURATION).milliseconds)
        }

        fun getLine(index: Int?): String {
            return index?.let { lyrics?.lines?.getOrNull(it)?.second } ?: ""
        }

        var currentLineIndex by remember { mutableStateOf(getLineIndex()) }
        var currentLine by remember { mutableStateOf(getLine(currentLineIndex)) }
        var nextLine by remember { mutableStateOf(getLine(currentLineIndex?.let { it + 1 })) }
        var visibility by remember { mutableStateOf(false) }
        val alpha = animateFloatAsState(if (visibility) 1f else 0f)

        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val isStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
        val isVisible = overlayVisibility > 0.01f
        val lyricsDisplay = preferences.lyricsDisplay

        fun clearLine() {
            currentLineIndex = null
            currentLine = ""
            nextLine = ""
            visibility = false
        }

        fun updateLine() {
            currentLineIndex = getLineIndex()
            currentLine = getLine(currentLineIndex)
            nextLine = getLine(currentLineIndex?.let { it + 1 })
            visibility =
                when (lyricsDisplay) {
                    LyricsDisplayPreference.DISABLED -> false // unreachable
                    LyricsDisplayPreference.DEFAULT -> currentLine.isNotEmpty()
                    LyricsDisplayPreference.TWO_LINES ->
                        currentLine.isNotEmpty() || nextLine.isNotEmpty()
                }
        }

        // Clear or refresh the overlay as soon as the lyrics or display mode changes,
        // even while it is hidden, so a stale line from the previous song is never
        // kept. Visibility and lifecycle changes alone leave the line untouched, so
        // it survives the overlay being hidden or the app leaving the foreground.
        LaunchedEffect(lyrics, lyricsDisplay) {
            if (lyrics == null || lyricsDisplay == LyricsDisplayPreference.DISABLED) {
                clearLine()
            } else {
                updateLine()
            }
        }

        LaunchedEffect(lyrics, lyricsDisplay, isVisible, isPlaying, isStarted) {
            if (
                lyrics == null ||
                    lyricsDisplay == LyricsDisplayPreference.DISABLED ||
                    !isVisible ||
                    !isStarted
            ) {
                return@LaunchedEffect
            }

            updateLine()
            if (isPlaying) {
                while (isActive) {
                    withFrameNanos { updateLine() }
                }
            } else {
                while (isActive) {
                    delay(1.seconds)
                    updateLine()
                }
            }
        }

        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides
                Density(density.density, density.fontScale * preferences.lyricsSizeMultiplier)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().alpha(overlayVisibility),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier =
                        Modifier.alpha(alpha.value)
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            .background(containerColor, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                ) {
                    when (preferences.lyricsDisplay) {
                        LyricsDisplayPreference.DISABLED -> {}
                        LyricsDisplayPreference.DEFAULT -> {
                            AnimatedContent(
                                currentLine,
                                transitionSpec = {
                                    fadeIn(emphasizedExit()) togetherWith fadeOut(emphasizedExit())
                                },
                                contentAlignment = Alignment.TopStart,
                            ) { animatedLine ->
                                Text(
                                    animatedLine,
                                    style = Typography.bodyLarge,
                                    color = contentColor,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        LyricsDisplayPreference.TWO_LINES -> {
                            AnimatedContent(
                                Pair(currentLine, nextLine),
                                transitionSpec = {
                                    fadeIn(emphasizedExit()) togetherWith fadeOut(emphasizedExit())
                                },
                                contentAlignment = Alignment.TopCenter,
                            ) { (animatedCurrentLine, animatedNextLine) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (animatedCurrentLine.isNotEmpty()) {
                                        Text(
                                            animatedCurrentLine,
                                            style = Typography.bodyLarge,
                                            color = contentColor,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                    if (animatedNextLine.isNotEmpty()) {
                                        Text(
                                            animatedNextLine,
                                            style = Typography.bodyLarge,
                                            color = contentColor.copy(alpha = INACTIVE_ALPHA),
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
