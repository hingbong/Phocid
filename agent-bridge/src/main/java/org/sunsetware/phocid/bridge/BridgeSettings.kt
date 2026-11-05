package org.sunsetware.phocid.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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

/**
 * Android 17 (API 37) blocks local network traffic - including inbound TCP connections to a
 * ServerSocket - unless ACCESS_LOCAL_NETWORK is granted. Android 16 and earlier grant that access
 * implicitly from INTERNET and do not know the permission, so checking or requesting it must
 * never happen below [SDK_LEVEL].
 */
object LocalNetworkPermission {
    /** API 37 (Android 17); Build.VERSION_CODES has no stable name for it yet. */
    const val SDK_LEVEL = 37

    /** True when [host] is only reachable once the runtime permission is granted on this device. */
    fun isRequired(host: String): Boolean =
        Build.VERSION.SDK_INT >= SDK_LEVEL && BridgeEndpoint.requiresLocalNetworkPermission(host)

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < SDK_LEVEL) return true
        return context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isMissing(context: Context, host: String): Boolean =
        isRequired(host) && !isGranted(context)
}
