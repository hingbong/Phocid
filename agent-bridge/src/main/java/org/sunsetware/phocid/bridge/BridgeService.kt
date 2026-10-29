package org.sunsetware.phocid.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BridgeState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val host: String = BridgeEndpoint.DEFAULT_HOST,
    val port: Int = BridgeEndpoint.DEFAULT_PORT,
    val error: String? = null,
)

class BridgeService : Service() {
    companion object {
        private const val CHANNEL_ID = "phocid_agent_bridge"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "org.sunsetware.phocid.bridge.action.STOP"
        private const val ACTION_RESTART = "org.sunsetware.phocid.bridge.action.RESTART"

        private val _state = MutableStateFlow(BridgeState())
        val state: StateFlow<BridgeState> = _state.asStateFlow()

        fun start(context: Context) {
            _state.update { it.copy(error = null, starting = !it.running) }
            try {
                context.startForegroundService(Intent(context, BridgeService::class.java))
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        starting = false,
                        error = "Could not start the bridge: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
            }
        }

        fun restart(context: Context) {
            _state.update { it.copy(error = null, starting = true) }
            try {
                context.startForegroundService(
                    Intent(context, BridgeService::class.java).setAction(ACTION_RESTART)
                )
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        starting = false,
                        error = "Could not restart the bridge: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
            }
        }

        fun stop(context: Context) {
            _state.update { it.copy(running = false, starting = false, error = null) }
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    private var server: McpServer? = null
    private var dataSource: BinderPhocidDataSource? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                _state.update { it.copy(running = false, starting = false, error = null) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> stopServer()
        }

        if (server != null) {
            _state.update { it.copy(running = true, starting = false) }
            return START_STICKY
        }

        val config = BridgeSettings.load(this)
        _state.update {
            it.copy(
                running = false,
                starting = true,
                host = config.host,
                port = config.port,
                error = null,
            )
        }
        var instance: McpServer? = null
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting local MCP server…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            val source = BinderPhocidDataSource(this)
            dataSource = source
            instance = McpServer(config.host, config.port, { TokenStore.getOrCreate(this) }, source)
            instance.start()
            server = instance
            _state.update {
                it.copy(
                    running = true,
                    starting = false,
                    host = config.host,
                    port = config.port,
                    error = null,
                )
            }
            updateNotification(notificationText(config))
            return START_STICKY
        } catch (t: Throwable) {
            instance?.stop()
            dataSource?.close()
            dataSource = null
            server = null
            _state.update {
                it.copy(
                    running = false,
                    starting = false,
                    host = config.host,
                    port = config.port,
                    error =
                        "Cannot listen on ${config.host}:${config.port}: " +
                            (t.message ?: t.javaClass.simpleName),
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopServer()
        _state.update { it.copy(running = false, starting = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServer() {
        server?.stop()
        server = null
        dataSource?.close()
        dataSource = null
        _state.update { it.copy(running = false) }
    }

    private fun notificationText(config: BridgeConfig): String {
        val endpoint = BridgeEndpoint.httpUrl(config.host, config.port)
        return if (BridgeEndpoint.isWildcard(config.host)) {
            "Listening on $endpoint (all interfaces)"
        } else {
            "Listening on $endpoint"
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Phocid agent bridge",
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setShowBadge(false)
        channel.description = "Keeps the local MCP server available to on-device agents"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP)
        val stopPending =
            PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentTitle("Phocid Agent Bridge")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPending,
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
