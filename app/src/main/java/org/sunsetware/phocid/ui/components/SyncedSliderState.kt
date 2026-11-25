package org.sunsetware.phocid.ui.components

import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Creates the [SliderState] that a caller-owned slider needs since Material 3 1.5.0.
 *
 * The `value`/`onValueChange` Slider overload lost its `thumb` and `track` parameters, so a slider
 * that customises either one has to drive the state-based overload. Mirror the contract the removed
 * overload implemented internally: the state is keyed on [steps] and [valueRange] so it cannot go
 * stale when a caller changes them, and the caller's [value] is written into it on every
 * composition, which keeps the caller in control of the thumb position.
 */
@Composable
fun rememberSyncedSliderState(
    value: Float,
    steps: Int = 0,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
): SliderState {
    val state = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
    state.value = value
    return state
}
