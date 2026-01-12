package com.arkadeep.shieldClan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CountDownTimer
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.util.Locale

class BlockService : AccessibilityService() {

    private val prefsName = "BlockerPrefs"
    private val blockedCache = HashMap<String, String>()

    // Notification Constants
    private val CHANNEL_ID = "shield_timer_channel"
    private val NOTIF_ID = 999
    private var timer: CountDownTimer? = null
    private var currentUnlockedPackage: String? = null

    // Receiver handles BLOCK updates, TIMER start, and STOP commands
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.arkadeep.shieldClan.UPDATE_BLOCKS" -> refreshCache()
                "com.arkadeep.shieldClan.START_TIMER" -> {
                    val duration = intent.getLongExtra("duration", 0L)
                    val pkg = intent.getStringExtra("package") ?: "App"
                    startUnlockTimer(duration, pkg)
                }
                "com.arkadeep.shieldClan.STOP_TIMER" -> {
                    // Triggered by the Notification Button
                    stopUnlockTimer()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info

        createNotificationChannel()
        refreshCache()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }

        val filter = IntentFilter().apply {
            addAction("com.arkadeep.shieldClan.UPDATE_BLOCKS")
            addAction("com.arkadeep.shieldClan.START_TIMER")
            addAction("com.arkadeep.shieldClan.STOP_TIMER") // Register new action
        }
        registerReceiver(updateReceiver, filter, flags)
    }

    override fun onDestroy() {
        timer?.cancel()
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) { }
        super.onDestroy()
    }

    // --- TIMER & NOTIFICATION LOGIC ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Unlock Timer"
            val descriptionText = "Controls for temporary app access"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startUnlockTimer(durationMs: Long, pkgName: String) {
        timer?.cancel()
        currentUnlockedPackage = pkgName

        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                updateNotification(millisUntilFinished, pkgName)
            }

            override fun onFinish() {
                stopUnlockTimer()
            }
        }.start()
    }

    private fun stopUnlockTimer() {
        timer?.cancel()
        cancelNotification()

        // CRITICAL: WIPE THE TIMESTAMP FROM STORAGE
        // This ensures the app is blocked immediately on the next event
        if (currentUnlockedPackage != null) {
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            prefs.edit().remove("temp_unlock_$currentUnlockedPackage").apply()
            currentUnlockedPackage = null
        }
    }

    private fun updateNotification(millisLeft: Long, pkgName: String) {
        val totalDuration = 3 * 60 * 1000

        val minutes = (millisLeft / 1000) / 60
        val seconds = (millisLeft / 1000) % 60
        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ACTION 1: Open Shield Clan (To Add New Apps)
        // We target MainActivity directly to bypass hidden icon issues
        val openAppIntent = Intent(this, MainActivity::class.java)
        openAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingOpenApp = PendingIntent.getActivity(
            this, 10, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ACTION 2: Re-Lock Immediately
        val stopIntent = Intent("com.arkadeep.shieldClan.STOP_TIMER")
        val pendingStop = PendingIntent.getBroadcast(
            this, 20, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Access: $pkgName")
            .setContentText("Time left: $timeString")
            .setProgress(totalDuration, (totalDuration - millisLeft).toInt(), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

            // Tapping the notification body opens Shield Clan
            .setContentIntent(pendingOpenApp)

            // Button 1: Re-Lock
            .addAction(android.R.drawable.ic_lock_lock, "Re-Lock Now", pendingStop)

            // Button 2: Open Shield App
            .addAction(android.R.drawable.ic_menu_add, "Manage Apps", pendingOpenApp)

        notificationManager.notify(NOTIF_ID, builder.build())
    }

    private fun cancelNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIF_ID)
    }

    // --- BLOCK LOGIC ---

    private fun refreshCache() {
        blockedCache.clear()
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val jsonString = prefs.getString("blocks_json", "[]")
        try {
            val arr = JSONArray(jsonString)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                blockedCache[o.getString("package")] = o.getString("name")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.packageName != null) {
            checkAndBlock(event.packageName.toString())
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val windows = windows
            if (!windows.isNullOrEmpty()) {
                for (window in windows) {
                    val rootNode = window.root { return@root }
                    val pkgName = rootNode?.packageName
                    if (pkgName != null) {
                        checkAndBlock(pkgName.toString())
                    }
                }
            }
        }
    }

    private fun checkAndBlock(pkg: String) {
        // Don't block ourselves or System UI
        if (pkg == packageName || pkg == "com.android.systemui") return

        if (isTemporarilyUnlocked(pkg)) return

        if (blockedCache.containsKey(pkg)) {
            // 1. KILL THE APP (Fixes PiP Glitch)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. SHOW BLOCK SCREEN
            val intent = Intent(this, BlockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra("blocked_package", pkg)
            intent.putExtra("blocked_name", blockedCache[pkg])
            startActivity(intent)
        }
    }
    private fun isTemporarilyUnlocked(pkg: String): Boolean {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val expiryTime = prefs.getLong("temp_unlock_$pkg", 0L)
        // If current time is LESS than expiry, we are unlocked.
        // If we removed the key in stopUnlockTimer, this returns 0L, so we block.
        return System.currentTimeMillis() < expiryTime
    }

    private fun AccessibilityWindowInfo.root(block: () -> Unit) = try {
        root
    } catch (e: Exception) {
        block()
        null
    }

    override fun onInterrupt() {}
}