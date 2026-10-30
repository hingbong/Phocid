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
    @Volatile private var lastFailure = BindFailure.NONE

    private enum class BindFailure {
        NONE,
        BIND_REJECTED,
        TIMEOUT,
        DISCONNECTED,
        REMOTE,
        PERMISSION,
    }

    /** A connected service and the bind generation that produced it. */
    private class QuerySession(val service: IPhocidQuery, val generation: Long)

    /** The session a call ran on, if it reached the service, and its JSON result. */
    private class CallResult(val session: QuerySession?, val result: String)

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

    private fun currentSession(): QuerySession? = synchronized(lock) { currentSessionLocked() }

    private fun currentSessionLocked(): QuerySession? {
        val service = query ?: return null
        val current = attempt ?: return null
        if (!retry.isCurrent(current.generation)) return null
        return QuerySession(service, current.generation)
    }

    private fun ensureQuery(): QuerySession? {
        currentSession()?.let {
            return it
        }
        var selected: BindAttempt? = null
        var startBinding = false
        synchronized(lock) {
            currentSessionLocked()?.let {
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
            return currentSession()
        }
        failAttempt(
            active,
            BindFailure.TIMEOUT,
            unbind = true,
            onlyWhileBinding = true,
        )
        return currentSession()
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

    override fun capabilities(): String = callWithSession { it.service.capabilities() }.result

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

    private fun call(requiredApi: Int = 1, block: (IPhocidQuery) -> String): String =
        callWithSession(requiredApi) { block(it.service) }.result

    private fun callWithSession(
        requiredApi: Int = 1,
        block: (QuerySession) -> String,
    ): CallResult {
        var staleDecisions = 0
        while (true) {
            val session = ensureQuery() ?: return CallResult(null, unavailableError())
            val version =
                if (requiredApi > 1) {
                    queryApiVersion(session)
                        ?: return CallResult(
                            session,
                            errorJson(
                                "phocid_error",
                                "Phocid data service did not report its API version",
                            ),
                        )
                } else {
                    MIN_API_VERSION
                }
            val decision =
                synchronized(lock) {
                    decideApiVersion(
                        requiredApi,
                        version,
                        session.service,
                        session.generation,
                        query,
                        retry,
                    )
                }
            when (decision) {
                ApiDecision.ALLOWED -> return invokeSession(session, block)
                ApiDecision.UNSUPPORTED ->
                    return CallResult(
                        null,
                        errorJson(
                            "unsupported_api",
                            "Phocid main app is too old; update it to use this tool",
                        ),
                    )
                ApiDecision.STALE_SESSION -> {
                    // The capability answer came from a session that has since been replaced.
                    // Discard it and evaluate against the session that is current now.
                    if (staleDecisions >= MAX_STALE_DECISION_RETRIES) {
                        return CallResult(
                            null,
                            errorJson(
                                "phocid_unavailable",
                                "Phocid data service kept changing during the call",
                            ),
                        )
                    }
                    staleDecisions += 1
                }
            }
        }
    }

    /** Probes the exact session that is about to run the call; never a cross-session cache. */
    private fun queryApiVersion(session: QuerySession): Int? =
        try {
            JSONObject(session.service.capabilities()).optInt("apiVersion", MIN_API_VERSION)
        } catch (_: RemoteException) {
            invalidateQuery(session.service, BindFailure.REMOTE)
            null
        } catch (_: SecurityException) {
            invalidateQuery(session.service, BindFailure.PERMISSION)
            null
        } catch (_: Exception) {
            null
        }

    private fun invokeSession(
        session: QuerySession,
        block: (QuerySession) -> String,
    ): CallResult =
        try {
            CallResult(session, block(session))
        } catch (e: RemoteException) {
            invalidateQuery(session.service, BindFailure.REMOTE)
            CallResult(session, errorJson("phocid_error", e.message ?: e.javaClass.simpleName))
        } catch (e: SecurityException) {
            invalidateQuery(session.service, BindFailure.PERMISSION)
            CallResult(
                session,
                errorJson("phocid_permission", e.message ?: "Signature permission rejected"),
            )
        } catch (e: Exception) {
            CallResult(session, errorJson("phocid_error", e.message ?: e.javaClass.simpleName))
        }

    private fun unavailableError(): String =
        errorJson(
            "phocid_unavailable",
            if (lastFailure == BindFailure.BIND_REJECTED) {
                "Phocid data service is not installed or is too old"
            } else {
                "Phocid data service did not connect"
            },
        )

    override fun close() {
        var current: BindAttempt? = null
        synchronized(lock) {
            if (retry.state == BinderConnectionState.CLOSED) return
            current = attempt
            attempt = null
            query = null
            retry.close()
            current?.latch?.countDown()
        }
        current?.let { runCatching { appContext.unbindService(it) } }
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()

    companion object {
        private const val MAIN_PACKAGE = "org.sunsetware.phocid.next"
        private const val SERVICE_CLASS = "org.sunsetware.phocid.agent.AgentQueryService"
        private const val BIND_TIMEOUT_MS = 1_500L
        private const val BIND_COOLDOWN_MS = 1_000L
        private const val MIN_API_VERSION = 1
        private const val MAX_STALE_DECISION_RETRIES = 3
    }
}

/**
 * Whether a result read through [service] from bind [generation] still describes the current
 * connection. A service that was replaced, or a generation that a newer attempt superseded, must
 * not have its earlier observations trusted.
 */
internal fun isCurrentQuery(
    connected: IPhocidQuery?,
    service: IPhocidQuery,
    retry: BinderRetryState,
    generation: Long,
): Boolean =
    connected === service &&
        retry.state == BinderConnectionState.BOUND &&
        retry.isCurrent(generation)

/** The outcome of checking a session's reported API version against a call's requirement. */
internal enum class ApiDecision {
    ALLOWED,
    UNSUPPORTED,
    STALE_SESSION,
}

/**
 * Whether an API-version answer queried from [service] at [generation] is still authoritative for
 * the session a call would run on. A reconnected or replaced service has a new generation, so its
 * predecessor's capability answer must not gate the new call.
 */
internal fun isQueriedVersionAuthoritative(
    service: IPhocidQuery,
    generation: Long,
    connected: IPhocidQuery?,
    retry: BinderRetryState,
): Boolean = isCurrentQuery(connected, service, retry, generation)

/**
 * Decides whether a call requiring [requiredApi] may run, using only the capability answer
 * [queriedVersion] that was read from [service] at [generation].
 *
 * [ApiDecision.STALE_SESSION] means the session changed while its capabilities were being queried,
 * so the caller must re-evaluate against the session that is current now; the stale answer must
 * neither authorize nor reject the call. Calls with [requiredApi] <= 1 need no capability probe,
 * but their session is still fenced the same way.
 */
internal fun decideApiVersion(
    requiredApi: Int,
    queriedVersion: Int,
    service: IPhocidQuery,
    generation: Long,
    connected: IPhocidQuery?,
    retry: BinderRetryState,
): ApiDecision {
    if (!isQueriedVersionAuthoritative(service, generation, connected, retry)) {
        return ApiDecision.STALE_SESSION
    }
    return if (requiredApi <= 1 || queriedVersion >= requiredApi) {
        ApiDecision.ALLOWED
    } else {
        ApiDecision.UNSUPPORTED
    }
}
