package org.sunsetware.phocid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sunsetware.phocid.ui.theme.emphasizedExit
import org.sunsetware.phocid.utils.wrap

@Stable
/** Group state updates together to prevent flickering. */
data class CarouselStateBatch<T>(
    val state: T,
    val count: Int,
    val repeat: Boolean,
    val lastIndex: Int,
    val lastIndexEquality: Any?,
    val currentIndex: Int,
    val previousIndex: Int?,
    val nextIndex: Int?,
)

/**
 * Differences between this and Google's Pager:
 * - supports repetition
 * - supports jumping
 * - has 99% less bugs than Google's POS
 *
 * @param content Index is not guaranteed to be valid; this lambda should check for validity itself.
 */
@Composable
inline fun <reified T> TrackCarousel(
    state: T,
    key: Any?,
    swipeThreshold: Dp,
    crossinline countSelector: @DisallowComposableCalls (T) -> Int,
    crossinline indexSelector: @DisallowComposableCalls (T) -> Int,
    crossinline repeatSelector: @DisallowComposableCalls (T) -> Boolean,
    crossinline indexEqualitySelector: @DisallowComposableCalls (T, Int) -> Any?,
    tapKey: Any?,
    crossinline onTap: () -> Unit,
    crossinline onVerticalDrag: suspend PointerInputScope.() -> Unit,
    crossinline onPrevious: () -> Unit,
    crossinline onNext: () -> Unit,
    modifier: Modifier = Modifier,
    crossinline content: @Composable (T, Int) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val dispatcher = Dispatchers.Main.limitedParallelism(1)

    var horizontalDragTotal by remember { mutableFloatStateOf(0f) }
    // Drags own the offset; a track change during a drag must not start a settle animation that
    // fights the finger.
    var isDragging by remember { mutableStateOf(false) }

    var stateBatch by remember {
        val count = countSelector(state)
        val repeat = repeatSelector(state)
        val index = indexSelector(state)
        mutableStateOf(
            CarouselStateBatch(
                state,
                count,
                repeat,
                index,
                indexEqualitySelector(state, index),
                index,
                (index - 1).wrap(count, repeat),
                (index + 1).wrap(count, repeat),
            )
        )
    }
    var isLastAdjacent by remember { mutableStateOf(true) }
    var lastNonadjacentDirection by remember { mutableIntStateOf(-1) }
    val offset = remember { Animatable(0f) }
    val updatedSwipeThreshold by rememberUpdatedState(swipeThreshold)

    @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
    LaunchedEffect(state, key) {
        withContext(dispatcher) {
            var (
                _,
                count,
                repeat,
                lastIndex,
                lastIndexEquality,
                currentIndex,
                previousIndex,
                nextIndex) =
                stateBatch
            val index = indexSelector(state)
            count = countSelector(state)
            repeat = repeatSelector(state)
            val currentIndexEquality = indexEqualitySelector(state, index)
            val indexChanged = lastIndexEquality != currentIndexEquality
            if (!indexChanged) {
                isLastAdjacent = true
            } else if (previousIndex == nextIndex && index == previousIndex) {
                isLastAdjacent = true
                offset.snapTo(offset.value.let { if (it < 0) it + 1 else it - 1 })
            } else {
                when (index) {
                    nextIndex -> {
                        isLastAdjacent = true
                        offset.snapTo(offset.value + 1)
                    }
                    previousIndex -> {
                        isLastAdjacent = true
                        offset.snapTo(offset.value - 1)
                    }
                    currentIndex -> {
                        isLastAdjacent = true
                    }
                    else -> {
                        isLastAdjacent = false
                        lastNonadjacentDirection = if (index >= currentIndex) -1 else 1
                        offset.snapTo(offset.value - lastNonadjacentDirection)
                    }
                }
            }
            lastIndexEquality = currentIndexEquality
            lastIndex = currentIndex
            currentIndex = index
            previousIndex = (index - 1).wrap(count, repeat)
            nextIndex = (index + 1).wrap(count, repeat)

            stateBatch =
                CarouselStateBatch(
                    state,
                    count,
                    repeat,
                    lastIndex,
                    lastIndexEquality,
                    currentIndex,
                    previousIndex,
                    nextIndex,
                )

            // Only settle when the index actually moved. Several version bumps arrive per track
            // transition, and re-running the settle on each of them restarts it mid-flight, which
            // reads as stutter. A bump that leaves the index alone must not touch the offset.
            if (indexChanged && !isDragging) {
                offset.animateTo(0f, emphasizedExit())
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
                .pointerInput(tapKey) { detectTapGestures { onTap() } }
                .pointerInput(Unit) { onVerticalDrag() }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            horizontalDragTotal = 0f
                            isLastAdjacent = true
                            isDragging = true
                        },
                        onDragCancel = {
                            isDragging = false
                            coroutineScope.launch { offset.animateTo(0f) }
                            horizontalDragTotal = 0f
                            isLastAdjacent = true
                        },
                        onDragEnd = {
                            // Cleared before the swipe commits, so the transition it triggers is
                            // allowed to settle the offset.
                            isDragging = false
                            coroutineScope.launch(dispatcher) {
                                val positionalThreshold =
                                    updatedSwipeThreshold.roundToPx().coerceAtMost(size.width / 2)
                                try {
                                    if (horizontalDragTotal >= positionalThreshold) {
                                        coroutineScope.launch { offset.animateTo(1f) }
                                        onPrevious()
                                    } else if (horizontalDragTotal <= -positionalThreshold) {
                                        coroutineScope.launch { offset.animateTo(-1f) }
                                        onNext()
                                    } else {
                                        coroutineScope.launch { offset.animateTo(0f) }
                                    }
                                } finally {
                                    horizontalDragTotal = 0f
                                    isLastAdjacent = true
                                }
                            }
                        },
                    ) { change, dragAmount ->
                        horizontalDragTotal += dragAmount
                        isLastAdjacent = true
                        coroutineScope.launch { offset.snapTo(horizontalDragTotal / size.width) }
                    }
                }
    ) {
        if (stateBatch.count > 0) {
            key(stateBatch.currentIndex) {
                Box(
                    modifier =
                        Modifier.graphicsLayer { translationX = (0 + offset.value) * size.width }
                ) {
                    content(stateBatch.state, stateBatch.currentIndex)
                }
            }
            if (isLastAdjacent) {
                if (stateBatch.previousIndex != null) {
                    key(stateBatch.previousIndex!!) {
                        Box(
                            modifier =
                                Modifier.graphicsLayer {
                                    translationX = (-1 + offset.value) * size.width
                                }
                        ) {
                            content(stateBatch.state, stateBatch.previousIndex!!)
                        }
                    }
                }
                if (stateBatch.nextIndex != null) {
                    key(stateBatch.nextIndex!!) {
                        Box(
                            modifier =
                                Modifier.graphicsLayer {
                                    translationX = (1 + offset.value) * size.width
                                }
                        ) {
                            content(stateBatch.state, stateBatch.nextIndex!!)
                        }
                    }
                }
            } else {
                key(stateBatch.lastIndex) {
                    Box(
                        modifier =
                            Modifier.graphicsLayer {
                                translationX =
                                    (lastNonadjacentDirection + offset.value) * size.width
                            }
                    ) {
                        content(stateBatch.state, stateBatch.lastIndex)
                    }
                }
            }
        }
    }
}
