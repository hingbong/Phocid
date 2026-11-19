package org.sunsetware.phocid

import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.PlayerManager
import org.sunsetware.phocid.data.PlayerState
import org.sunsetware.phocid.data.PlayerTransientState

@RunWith(RobolectricTestRunner::class)
class PlayerManagerTest {
    @Test
    fun closeWithoutInitializeIsSafe() {
        val manager =
            PlayerManager(MutableStateFlow(PlayerState()), MutableStateFlow(PlayerTransientState()))

        // close() used to release the lateinit controller unconditionally, crashing whenever
        // teardown won the race with initialization.
        manager.close()

        assertThat(manager.state.value).isEqualTo(PlayerState())
    }
}
