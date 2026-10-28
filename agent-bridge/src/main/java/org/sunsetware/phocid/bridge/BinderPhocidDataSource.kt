package org.sunsetware.phocid.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.sunsetware.phocid.agent.IPhocidQuery

/**
 * Binds to the main Phocid app's signature-protected query service.
 *
 * The service is optional: if Phocid is not installed or is an older build without the service,
 * tools fail with a readable JSON error instead of crashing the bridge.
 */
class BinderPhocidDataSource(context: Context) : PhocidDataSource, AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val connected = CountDownLatch(1)

    @Volatile private var query: IPhocidQuery? = null
    @Volatile private var bindAttempted = false
    @Volatile private var bindFailed = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(lock) {
                    query = service?.let { IPhocidQuery.Stub.asInterface(it) }
                    connected.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) { query = null }
            }

            override fun onBindingDied(name: ComponentName?) {
                synchronized(lock) {
                    query = null
                    connected.countDown()
                }
            }
        }

    private fun ensureQuery(): IPhocidQuery? {
        query?.let { return it }
        synchronized(lock) {
            query?.let { return it }
            if (!bindAttempted) {
                bindAttempted = true
                try {
                    val intent =
                        Intent()
                            .setClassName(MAIN_PACKAGE, SERVICE_CLASS)
                    val bound =
                        appContext.bindService(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    if (!bound) {
                        bindFailed = true
                        connected.countDown()
                    }
                } catch (_: Exception) {
                    bindFailed = true
                    connected.countDown()
                }
            }
        }
        connected.await(1_500, TimeUnit.MILLISECONDS)
        return query
    }

    override fun capabilities(): String = call { it.capabilities() }

    override fun searchTracks(requestJson: String): String = call { it.searchTracks(requestJson) }

    override fun getTrack(libraryId: Long): String = call { it.getTrack(libraryId) }

    override fun statsSummary(range: String): String = call { it.statsSummary(range) }

    override fun statsTopTracks(requestJson: String): String = call { it.statsTopTracks(requestJson) }

    override fun statsDaily(range: String): String = call { it.statsDaily(range) }

    override fun resolveStatsUuid(libraryId: Long): String = call { it.resolveStatsUuid(libraryId) }

    private fun call(block: (IPhocidQuery) -> String): String {
        val service = ensureQuery()
        if (service == null) {
            return errorJson(
                "phocid_unavailable",
                if (bindFailed) "Phocid data service is not installed or is too old"
                else "Phocid data service did not connect",
            )
        }
        return try {
            block(service)
        } catch (e: RemoteException) {
            query = null
            errorJson("phocid_error", e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            errorJson("phocid_permission", e.message ?: "Signature permission rejected")
        } catch (e: Exception) {
            errorJson("phocid_error", e.message ?: e.javaClass.simpleName)
        }
    }

    override fun close() {
        synchronized(lock) {
            query = null
            if (bindAttempted) {
                try {
                    appContext.unbindService(connection)
                } catch (_: Exception) {}
            }
            bindAttempted = false
        }
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    companion object {
        private const val MAIN_PACKAGE = "org.sunsetware.phocid.next"
        private const val SERVICE_CLASS = "org.sunsetware.phocid.agent.AgentQueryService"
    }
}
