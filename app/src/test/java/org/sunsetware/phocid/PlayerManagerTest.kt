package org.sunsetware.phocid

import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.PlayerManager
import org.sunsetware.phocid.data.PlayerState
import org.sunsetware.phocid.data.PlayerTransientState
import org.sunsetware.phocid.data.awaitOrRelease

@RunWith(RobolectricTestRunner::class)
class PlayerManagerTest {
    /** Queues tasks so tests control exactly when the future's listener/release tasks run. */
    private val pendingTasks = ArrayDeque<Runnable>()

    private val queuedExecutor = Executor { pendingTasks.addLast(it) }

    private fun drainTasks() {
        while (pendingTasks.isNotEmpty()) {
            pendingTasks.removeFirst().run()
        }
    }

    @Test
    fun closeWithoutInitializeIsSafe() {
        val manager =
            PlayerManager(MutableStateFlow(PlayerState()), MutableStateFlow(PlayerTransientState()))

        // close() used to release the lateinit controller unconditionally, crashing whenever
        // teardown won the race with initialization.
        manager.close()

        assertThat(manager.state.value).isEqualTo(PlayerState())
    }

    @Test
    fun awaitOrReleaseReturnsValueWithoutReleasingOnSuccess() {
        val future = SettableFuture.create<String>()
        val released = mutableListOf<String>()
        runBlocking {
            val deferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    future.awaitOrRelease(queuedExecutor) { released.add(it) }
                }
            future.set("controller")
            drainTasks()

            assertThat(deferred.await()).isEqualTo("controller")
        }
        assertThat(released).isEmpty()
    }

    @Test
    fun awaitOrReleaseReleasesOnceWhenCancellationRacesCompletedFuture() {
        val future = SettableFuture.create<String>()
        val released = mutableListOf<String>()
        runBlocking {
            val deferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    future.awaitOrRelease(queuedExecutor) { released.add(it) }
                }
            // The future completes (a real controller is built) but its listener has not run
            // yet, so the cancelled continuation can never receive the value.
            future.set("controller")
            // Timeout/teardown cancels the wait; cancel(true) is a no-op on a completed future,
            // which used to leak the built controller and its session connection.
            deferred.cancel()
            drainTasks()

            assertThat(deferred.isCancelled).isTrue()
        }
        // Both the cancellation handler and the late listener attempt the release; the atomic
        // guard must deduplicate them to exactly one.
        assertThat(released).containsExactly("controller")
    }

    @Test
    fun awaitOrReleaseCancelsPendingFutureWithoutReleasing() {
        val future = SettableFuture.create<String>()
        val released = mutableListOf<String>()
        runBlocking {
            val deferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    future.awaitOrRelease(queuedExecutor) { released.add(it) }
                }
            deferred.cancel()
            drainTasks()

            assertThat(deferred.isCancelled).isTrue()
            assertThat(future.isCancelled).isTrue()
        }
        assertThat(released).isEmpty()
    }
}
