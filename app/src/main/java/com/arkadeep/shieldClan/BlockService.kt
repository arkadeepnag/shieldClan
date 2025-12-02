package com.arkadeep.shieldClan

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray

class BlockService : AccessibilityService() {

    private val prefsName = "BlockerPrefs"

    private val blockedCache = HashMap<String, String>()


    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshCache()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshCache()

        val filter = IntentFilter("com.arkadeep.shieldClan.UPDATE_BLOCKS")
        registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED)

    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(updateReceiver)
    }

    private fun refreshCache() {
        blockedCache.clear()
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("blocks_json", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            blockedCache[o.optString("package")] = o.optString("name")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return


        if (pkg == packageName) return


        if (blockedCache.containsKey(pkg)) {
            val appName = blockedCache[pkg]

            val intent = Intent(this, BlockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra("blocked_package", pkg)
            intent.putExtra("blocked_name", appName)
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}
}