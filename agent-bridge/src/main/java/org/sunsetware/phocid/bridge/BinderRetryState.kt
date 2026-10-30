package org.sunsetware.phocid.bridge

/** States for the bridge's connection to the main app's Binder query service. */
internal enum class BinderConnectionState {
    UNBOUND,
    BINDING,
    BOUND,
    FAILED,
    CLOSED,
}

/**
 * Pure retry/fencing state for [BinderPhocidDataSource].
 *
 * Every bind attempt gets a new generation. Callbacks from an older generation, or from an attempt
 * that already timed out, must be ignored so a late callback cannot revive a closed client or
 * replace a newer connection. Failures set a short cooldown instead of poisoning the connection for
 * the process lifetime.
 */
internal class BinderRetryState(
    private val cooldownMs: Long,
    private val now: () -> Long,
) {
    var state: BinderConnectionState = BinderConnectionState.UNBOUND
        private set

    var generation: Long = 0L
        private set

    private var retryAtMs: Long = Long.MIN_VALUE

    fun canBeginAttempt(): Boolean =
        when (state) {
            BinderConnectionState.UNBOUND -> true
            BinderConnectionState.FAILED -> now() >= retryAtMs
            else -> false
        }

    /** Reserves the single in-flight attempt and returns its generation, or null if not allowed. */
    fun beginAttempt(): Long? {
        if (!canBeginAttempt()) return null
        generation += 1
        state = BinderConnectionState.BINDING
        retryAtMs = Long.MIN_VALUE
        return generation
    }

    fun isCurrent(generation: Long): Boolean =
        state != BinderConnectionState.CLOSED && generation == this.generation

    fun markBound(generation: Long): Boolean {
        if (state != BinderConnectionState.BINDING || !isCurrent(generation)) return false
        state = BinderConnectionState.BOUND
        return true
    }

    fun markFailed(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        state = BinderConnectionState.FAILED
        retryAtMs = now() + cooldownMs
        return true
    }

    fun close() {
        if (state == BinderConnectionState.CLOSED) return
        state = BinderConnectionState.CLOSED
        generation += 1
        retryAtMs = Long.MIN_VALUE
    }
}
