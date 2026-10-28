package org.sunsetware.phocid.bridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusValue: TextView
    private lateinit var urlValue: TextView
    private lateinit var tokenValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        root.addView(
            TextView(this).apply {
                text = "Phocid Agent Bridge"
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
            },
            matchWrap(),
        )

        root.addView(
            TextView(this).apply {
                text =
                    "Local MCP server for OpenMinis and other on-device agents. " +
                        "It listens only on 127.0.0.1 and requires the token below."
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(8), 0, dp(24))
            },
            matchWrap(),
        )

        root.addView(label("Status"), matchWrap())
        statusValue = value("Stopped")
        root.addView(statusValue, matchWrap())

        root.addView(label("MCP URL"), matchWrap())
        urlValue = value("http://127.0.0.1:" + BridgeService.PORT + "/mcp")
        root.addView(urlValue, matchWrap())

        root.addView(label("Bearer token"), matchWrap())
        tokenValue = value("")
        root.addView(tokenValue, matchWrap())

        root.addView(label("Battery"), matchWrap())
        batteryValue = value("")
        root.addView(batteryValue, matchWrap())
        root.addView(
            button("Battery settings") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            matchWrap(),
        )

        root.addView(
            TextView(this).apply {
                text =
                    "OpenMinis → Add MCP server → HTTP\n" +
                        "URL: the value above\n" +
                        "Header: Authorization: Bearer <token>\n" +
                        "Authorization: None"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(16), 0, dp(16))
            },
            matchWrap(),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        startButton = button("Start") {
            BridgeService.start(this)
            delayedRefresh()
        }
        stopButton = button("Stop") {
            BridgeService.stop(this)
            delayedRefresh()
        }
        actions.addView(startButton, weightWrap())
        actions.addView(stopButton, weightWrap())
        root.addView(actions, matchWrap())

        val copyActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        copyActions.addView(
            button("Copy URL") {
                copy("Phocid MCP URL", urlValue.text.toString())
            },
            weightWrap(),
        )
        copyActions.addView(
            button("Copy token") {
                copy("Phocid MCP token", tokenValue.text.toString())
            },
            weightWrap(),
        )
        root.addView(copyActions, matchWrap())

        root.addView(
            button("Generate new token") {
                TokenStore.regenerate(this)
                refresh()
                Toast.makeText(this, "Token changed; update OpenMinis", Toast.LENGTH_LONG).show()
            },
            matchWrap(),
        )

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun refresh() {
        val running = BridgeService.isRunning
        val error = BridgeService.lastError
        statusValue.text =
            if (running) "Running"
            else if (error.isNullOrBlank()) "Stopped"
            else "Stopped: " + error
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        urlValue.text = "http://127.0.0.1:" + BridgeService.PORT + "/mcp"
        tokenValue.text = TokenStore.getOrCreate(this)
        val power = getSystemService(PowerManager::class.java)
        val unrestricted = power.isIgnoringBatteryOptimizations(packageName)
        batteryValue.text =
            if (unrestricted) "Unrestricted"
            else "Optimized — background freeze likely"
        batteryValue.setTextColor(
            if (unrestricted) Color.rgb(0, 100, 0) else Color.rgb(180, 0, 0)
        )
    }

    private fun delayedRefresh() {
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 400)
    }

    private fun copy(label: String, text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun value(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(242, 242, 242))
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun weightWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
