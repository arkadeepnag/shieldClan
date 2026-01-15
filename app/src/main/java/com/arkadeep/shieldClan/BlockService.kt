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

    // Timer Variables
    private val CHANNEL_ID = "shield_timer_channel"
    private val NOTIF_ID = 999
    private var timer: CountDownTimer? = null
    private var currentUnlockedPackage: String? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.arkadeep.shieldClan.UPDATE_BLOCKS" -> refreshCache()
                "com.arkadeep.shieldClan.START_TIMER" -> {
                    val duration = intent.getLongExtra("duration", 0L)
                    val pkg = intent.getStringExtra("package") ?: "App"
                    startUnlockTimer(duration, pkg)
                }
                "com.arkadeep.shieldClan.STOP_TIMER" -> stopUnlockTimer()
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

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        val filter = IntentFilter().apply {
            addAction("com.arkadeep.shieldClan.UPDATE_BLOCKS")
            addAction("com.arkadeep.shieldClan.START_TIMER")
            addAction("com.arkadeep.shieldClan.STOP_TIMER")
        }
        registerReceiver(updateReceiver, filter, flags)
    }

    override fun onDestroy() {
        timer?.cancel()
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    // --- TIMER & ROLLOVER LOGIC ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Unlock Timer", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)

        if (currentUnlockedPackage != null) {
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

            // --- ROLLOVER FEATURE: Save unused time ---
            val expiry = prefs.getLong("temp_unlock_$currentUnlockedPackage", 0L)
            val now = System.currentTimeMillis()

            // If we are stopping EARLY (Time is still left)
            if (expiry > now) {
                val remaining = expiry - now
                val existingCredit = prefs.getLong("saved_time_credit", 0L)

                // Add remaining time to credit bank
                prefs.edit().putLong("saved_time_credit", existingCredit + remaining).apply()
            }
            // ------------------------------------------

            // Remove the unlock key immediately
            prefs.edit().remove("temp_unlock_$currentUnlockedPackage").apply()
            currentUnlockedPackage = null
        }
    }

    private fun updateNotification(millisLeft: Long, pkgName: String) {
        val totalDuration = 3 * 60 * 1000 // Just for progress bar visual
        val minutes = (millisLeft / 1000) / 60
        val seconds = (millisLeft / 1000) % 60
        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(this, 10, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // This button calls STOP_TIMER above
        val stopIntent = Intent("com.arkadeep.shieldClan.STOP_TIMER")
        val pendingStop = PendingIntent.getBroadcast(this, 20, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Access: $pkgName")
            .setContentText("Time left: $timeString")
            .setProgress(totalDuration, (totalDuration - millisLeft).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_lock_lock, "Re-Lock & Save Time", pendingStop)
            .addAction(android.R.drawable.ic_menu_add, "Manage Apps", pendingOpen)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, builder.build())
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
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != null) checkAndBlock(event.packageName.toString())

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val windows = windows
            if (!windows.isNullOrEmpty()) {
                for (window in windows) {
                    val root = try { window.root } catch(e:Exception) { null }
                    if (root?.packageName != null) checkAndBlock(root.packageName.toString())
                }
            }
        }
    }

    private fun checkAndBlock(pkg: String) {
        if (pkg == packageName || pkg == "com.arkadeep.shieldClan") return
        if (pkg == "com.android.systemui") return
        if (isTemporarilyUnlocked(pkg)) return

        if (blockedCache.containsKey(pkg)) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
            } catch (e: Exception) {}

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
        return System.currentTimeMillis() < expiryTime
    }

    override fun onInterrupt() {}
}