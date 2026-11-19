package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.globals.GlobalData

@RunWith(RobolectricTestRunner::class)
class GlobalDataTest {
    @After
    fun resetInitializationState() {
        // GlobalData is a process-wide singleton; other test classes rely on its initial state.
        GlobalData.initialized.set(false)
        GlobalData.initializationFailed.set(false)
    }

    @Test
    fun awaitInitializedReturnsImmediatelyWhenAlreadyInitialized() {
        GlobalData.initialized.set(true)
        // Already-initialized callers never wait: the loop is not entered at all.
        assertThat(GlobalData.awaitInitialized()).isTrue()
    }

    @Test
    fun awaitInitializedFailsFastWhenInitializationFailed() {
        GlobalData.initialized.set(false)
        GlobalData.initializationFailed.set(true)
        // A recorded failure must return false on the first iteration instead of waiting out
        // the whole timeout bound on the main thread.
        assertThat(GlobalData.awaitInitialized()).isFalse()
    }
}
