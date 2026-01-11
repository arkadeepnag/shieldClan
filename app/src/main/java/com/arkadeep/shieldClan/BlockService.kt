package com.arkadeep.shieldClan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray

class BlockService : AccessibilityService() {

    private val prefsName = "BlockerPrefs"
    private val blockedCache = HashMap<String, String>()

    // Receiver to update block list immediately without restarting service
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshCache()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // CRITICAL: Configure service to see all windows (needed for PiP)
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info

        refreshCache()

        // Android 13+ compatibility for Receiver
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }

        registerReceiver(
            updateReceiver,
            IntentFilter("com.arkadeep.shieldClan.UPDATE_BLOCKS"),
            flags
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        super.onDestroy()
    }

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

        // 1. Direct Package Check (Standard Fullscreen)
        if (event.packageName != null) {
            checkAndBlock(event.packageName.toString())
        }

        // 2. PiP & Overlay Check (Scan all windows)
        // PiP windows trigger WINDOWS_CHANGED, not always STATE_CHANGED
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            val windows = windows // active windows list
            if (!windows.isNullOrEmpty()) {
                for (window in windows) {
                    val rootNode = window.root { return@root } // Safety extension
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

        if (blockedCache.containsKey(pkg)) {
            val intent = Intent(this, BlockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra("blocked_package", pkg)
            intent.putExtra("blocked_name", blockedCache[pkg])
            startActivity(intent)
        }
    }

    // Extension function to safely get root node
    private fun AccessibilityWindowInfo.root(block: () -> Unit) = try {
        root
    } catch (e: Exception) {
        block()
        null
    }

    override fun onInterrupt() {}
}