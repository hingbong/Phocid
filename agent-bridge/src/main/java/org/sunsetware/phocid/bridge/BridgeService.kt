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

class BridgeService : Service() {
    companion object {
        const val PORT = 8765

        private const val CHANNEL_ID = "phocid_agent_bridge"
        private const val NOTIFICATION_ID = 1

        @Volatile var isRunning = false
            private set

        @Volatile var lastError: String? = null
            private set

        fun start(context: Context) {
            lastError = null
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) {
            return START_NOT_STICKY
        }

        lastError = null
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting local MCP server…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            val instance = McpServer(PORT) { TokenStore.getOrCreate(this) }
            instance.start()
            server = instance
            isRunning = true
            updateNotification("Listening on http://127.0.0.1:" + PORT + "/mcp")
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            isRunning = false
            server = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Phocid Agent Bridge")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
