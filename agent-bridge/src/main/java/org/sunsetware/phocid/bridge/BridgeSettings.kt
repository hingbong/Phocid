package org.sunsetware.phocid.bridge

import android.content.Context

data class BridgeConfig(
    val host: String,
    val port: Int,
    val allowWrites: Boolean = false,
)

object BridgeSettings {
    private const val PREFS = "bridge"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_ALLOW_WRITES = "allow_writes"

    fun load(context: Context): BridgeConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host =
            prefs.getString(KEY_HOST, null)
                ?.let(BridgeEndpoint::normalizeHost)
                ?.takeIf { BridgeEndpoint.hostError(it) == null }
                ?: BridgeEndpoint.DEFAULT_HOST
        val port =
            prefs.getInt(KEY_PORT, BridgeEndpoint.DEFAULT_PORT)
                .takeIf { it in BridgeEndpoint.PORT_RANGE }
                ?: BridgeEndpoint.DEFAULT_PORT
        val allowWrites = prefs.getBoolean(KEY_ALLOW_WRITES, false)
        return BridgeConfig(host, port, allowWrites)
    }

    fun save(context: Context, config: BridgeConfig) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, BridgeEndpoint.normalizeHost(config.host))
            .putInt(KEY_PORT, config.port)
            .putBoolean(KEY_ALLOW_WRITES, config.allowWrites)
            .apply()
    }
}
