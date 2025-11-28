package com.arkadeep.shieldClan

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray

class BlockService : AccessibilityService() {

    private val prefsName = "BlockerPrefs"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return

            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val arr = JSONArray(prefs.getString("blocks_json", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("package") == pkg) {
                    val intent = Intent(this, BlockActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.putExtra("blocked_package", pkg)
                    intent.putExtra("blocked_name", o.optString("name"))
                    startActivity(intent)
                    return
                }
            }
        } catch (ex: Exception) {
            Log.e("BlockService", "err", ex)
        }
    }

    override fun onInterrupt() {}
    override fun onServiceConnected() { super.onServiceConnected() }
}
