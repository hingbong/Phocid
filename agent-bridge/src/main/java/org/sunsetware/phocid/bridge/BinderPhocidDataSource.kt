package org.sunsetware.phocid.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.sunsetware.phocid.agent.IPhocidQuery

/**
 * Binds to the main Phocid app's signature-protected query service.
 *
 * The service is optional: if Phocid is not installed or is an older build without the service,
 * tools fail with a readable JSON error instead of crashing the bridge. Binding is retryable: each
 * call may start a new attempt after a failure, with a short cooldown to avoid a rebind storm.
 */
class BinderPhocidDataSource(context: Context) : PhocidDataSource, AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val retry = BinderRetryState(BIND_COOLDOWN_MS) { SystemClock.elapsedRealtime() }

    /** The single in-flight or connected attempt; callbacks from any other attempt are stale. */
    private var attempt: BindAttempt? = null

    @Volatile private var query: IPhocidQuery? = null
    @Volatile private var apiVersion: Int? = null
    @Volatile private var lastFailure = BindFailure.NONE

    private enum class BindFailure {
        NONE,
        BIND_REJECTED,
        TIMEOUT,
        DISCONNECTED,
        REMOTE,
        PERMISSION,
    }

    private inner class BindAttempt(val generation: Long) : ServiceConnection {
        val latch = CountDownLatch(1)

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            handleConnected(this, service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            failAttempt(this, BindFailure.DISCONNECTED, unbind = true)
        }

        override fun onBindingDied(name: ComponentName?) {
            failAttempt(this, BindFailure.DISCONNECTED, unbind = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            failAttempt(this, BindFailure.BIND_REJECTED, unbind = true)
        }
    }

    private fun ensureQuery(): IPhocidQuery? {
        query?.let {
            return it
        }
        var selected: BindAttempt? = null
        var startBinding = false
        synchronized(lock) {
            query?.let {
                return it
            }
            if (retry.state == BinderConnectionState.CLOSED) return null
            val current = attempt
            if (retry.state == BinderConnectionState.BINDING && current != null) {
                selected = current
            } else {
                val generation = retry.beginAttempt() ?: return null
                selected =
                    BindAttempt(generation).also {
                        attempt = it
                        startBinding = true
                    }
            }
        }
        val active = selected ?: return null
        if (startBinding) startBind(active)

        if (active.latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return query
        }
        failAttempt(
            active,
            BindFailure.TIMEOUT,
            unbind = true,
            onlyWhileBinding = true,
        )
        return query
    }

    private fun startBind(attempt: BindAttempt) {
        var rejected = false
        synchronized(lock) {
            if (
                this.attempt !== attempt ||
                    retry.state != BinderConnectionState.BINDING ||
                    !retry.isCurrent(attempt.generation)
            ) {
                return
            }
            val bound =
                try {
                    appContext.bindService(
                        Intent().setClassName(MAIN_PACKAGE, SERVICE_CLASS),
                        attempt,
                        Context.BIND_AUTO_CREATE,
                    )
                } catch (_: Exception) {
                    false
                }
            rejected = !bound
        }
        if (rejected) {
            failAttempt(
                attempt,
                BindFailure.BIND_REJECTED,
                unbind = false,
                onlyWhileBinding = true,
            )
        }
    }

    private fun handleConnected(attempt: BindAttempt, service: IBinder?) {
        if (service == null) {
            failAttempt(
                attempt,
                BindFailure.BIND_REJECTED,
                unbind = true,
                onlyWhileBinding = true,
            )
            return
        }
        var stale = false
        synchronized(lock) {
            if (
                this.attempt === attempt &&
                    retry.isCurrent(attempt.generation) &&
                    (retry.state == BinderConnectionState.BINDING ||
                        retry.state == BinderConnectionState.BOUND)
            ) {
                query = IPhocidQuery.Stub.asInterface(service)
                apiVersion = null
                lastFailure = BindFailure.NONE
                retry.markBound(attempt.generation)
                attempt.latch.countDown()
            } else {
                stale = true
            }
        }
        if (stale) {
            // A timed-out or closed attempt must not revive the client. Release its binding.
            runCatching { appContext.unbindService(attempt) }
        }
    }

    private fun failAttempt(
        attempt: BindAttempt,
        failure: BindFailure,
        unbind: Boolean,
        onlyWhileBinding: Boolean = false,
    ) {
        var shouldUnbind = false
        synchronized(lock) {
            if (
                this.attempt === attempt &&
                    retry.isCurrent(attempt.generation) &&
                    (!onlyWhileBinding || retry.state == BinderConnectionState.BINDING)
            ) {
                retry.markFailed(attempt.generation)
                this.attempt = null
                query = null
                apiVersion = null
                lastFailure = failure
                attempt.latch.countDown()
                shouldUnbind = unbind
            }
        }
        if (shouldUnbind) runCatching { appContext.unbindService(attempt) }
    }

    private fun invalidateQuery(service: IPhocidQuery, failure: BindFailure) {
        var stale: BindAttempt? = null
        synchronized(lock) {
            if (query !== service) return
            query = null
            apiVersion = null
            val current = attempt
            if (current != null && retry.isCurrent(current.generation)) {
                retry.markFailed(current.generation)
                lastFailure = failure
                current.latch.countDown()
                stale = current
            }
            attempt = null
        }
        stale?.let { runCatching { appContext.unbindService(it) } }
    }

    override fun capabilities(): String {
        val result = call { it.capabilities() }
        if (!hasError(result)) {
            runCatching { apiVersion = JSONObject(result).optInt("apiVersion", 1) }
        }
        return result
    }

    override fun searchTracks(requestJson: String): String = call { it.searchTracks(requestJson) }

    override fun getTrack(libraryId: Long): String = call { it.getTrack(libraryId) }

    override fun statsSummary(range: String): String = call { it.statsSummary(range) }

    override fun statsTopTracks(requestJson: String): String = call {
        it.statsTopTracks(requestJson)
    }

    override fun statsDaily(range: String): String = call { it.statsDaily(range) }

    override fun resolveStatsUuid(libraryId: Long): String = call { it.resolveStatsUuid(libraryId) }

    override fun listFacets(requestJson: String): String =
        call(requiredApi = 2) { it.listFacets(requestJson) }

    override fun playerState(): String = call(requiredApi = 3) { it.playerState() }

    override fun updateSortKeys(requestJson: String): String =
        call(requiredApi = 4) { it.updateSortKeys(requestJson) }

    private fun supports(requiredApi: Int): Boolean {
        synchronized(lock) {
            apiVersion?.let { cached ->
                if (cached >= requiredApi) return true
                apiVersion = null
            }
        }
        val service = ensureQuery() ?: return false
        val version =
            try {
                JSONObject(service.capabilities()).optInt("apiVersion", 1)
            } catch (_: RemoteException) {
                invalidateQuery(service, BindFailure.REMOTE)
                return false
            } catch (_: SecurityException) {
                invalidateQuery(service, BindFailure.PERMISSION)
                return false
            } catch (_: Exception) {
                return false
            }
        synchronized(lock) {
            if (query === service) apiVersion = version
        }
        return version >= requiredApi
    }

    private fun call(requiredApi: Int = 1, block: (IPhocidQuery) -> String): String {
        if (requiredApi > 1 && !supports(requiredApi)) {
            return errorJson(
                "unsupported_api",
                "Phocid main app is too old; update it to use this tool",
            )
        }
        val service = ensureQuery()
        if (service == null) {
            return errorJson(
                "phocid_unavailable",
                if (lastFailure == BindFailure.BIND_REJECTED) {
                    "Phocid data service is not installed or is too old"
                } else {
                    "Phocid data service did not connect"
                },
            )
        }
        return try {
            block(service)
        } catch (e: RemoteException) {
            invalidateQuery(service, BindFailure.REMOTE)
            errorJson("phocid_error", e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            invalidateQuery(service, BindFailure.PERMISSION)
            errorJson("phocid_permission", e.message ?: "Signature permission rejected")
        } catch (e: Exception) {
            errorJson("phocid_error", e.message ?: e.javaClass.simpleName)
        }
    }

    override fun close() {
        var current: BindAttempt? = null
        synchronized(lock) {
            if (retry.state == BinderConnectionState.CLOSED) return
            current = attempt
            attempt = null
            query = null
            apiVersion = null
            retry.close()
            current?.latch?.countDown()
        }
        current?.let { runCatching { appContext.unbindService(it) } }
    }

    private fun hasError(value: String): Boolean = runCatching {
        JSONObject(value).has("error")
    }.getOrDefault(false)

    private fun errorJson(code: String, message: String): String =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()

    companion object {
        private const val MAIN_PACKAGE = "org.sunsetware.phocid.next"
        private const val SERVICE_CLASS = "org.sunsetware.phocid.agent.AgentQueryService"
        private const val BIND_TIMEOUT_MS = 1_500L
        private const val BIND_COOLDOWN_MS = 1_000L
    }
}
