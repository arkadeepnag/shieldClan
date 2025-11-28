package com.arkadeep.shieldClan

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class MainActivity : Activity() {

    data class AppInfo(val name: String, val packageName: String) {
        override fun toString(): String = name
    }

    private lateinit var spinnerApps: Spinner
    private lateinit var btnBlock: Button
    private lateinit var btnSetPin: Button
    private lateinit var btnPermissions: Button
    private lateinit var containerBlocks: LinearLayout
    private val installedApps = mutableListOf<AppInfo>()

    private val prefsName = "BlockerPrefs"
    private val PIN_ACTION_SET = "set_pin"
    private val PIN_ACTION_VERIFY_REMOVE = "verify_and_remove"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerApps = findViewById(R.id.spinnerApps)
        btnBlock = findViewById(R.id.btnStart)
        btnSetPin = findViewById(R.id.btnSetPin)
        btnPermissions = findViewById(R.id.btnPermissions)
        containerBlocks = findViewById(R.id.containerBlocks)

        loadInstalledApps()
        spinnerApps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, installedApps)

        btnPermissions.setOnClickListener { requestPermissions() }

        btnSetPin.setOnClickListener {
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("action", PIN_ACTION_SET)
            startActivity(i)
        }

        btnBlock.setOnClickListener {
            val selected = spinnerApps.selectedItem as? AppInfo
            if (selected == null) {
                Toast.makeText(this, "Pick an app", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            // Ensure PIN is set
            val salt = prefs.getString("pin_salt", null)
            val hash = prefs.getString("pin_hash", null)
            if (salt == null || hash == null) {
                Toast.makeText(this, "Set a security PIN first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            addInfiniteBlock(selected.packageName, selected.name)
            addCardAnimated(selected.packageName, selected.name)
            Toast.makeText(this, "Blocked ${selected.name}", Toast.LENGTH_SHORT).show()
        }

        // Initial render of existing blocks
        renderAllBlocks()
    }

    private fun requestPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Enable Display over other apps", Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable Accessibility (Focus Blocker)", Toast.LENGTH_LONG).show()
        }
    }


    private fun getBlocks(): JSONArray {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return JSONArray(prefs.getString("blocks_json", "[]"))
    }

    private fun saveBlocks(arr: JSONArray) {
        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString("blocks_json", arr.toString()).apply()
    }

    private fun addInfiniteBlock(pkg: String, name: String) {
        val arr = getBlocks()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("package") == pkg) return
        }
        val o = JSONObject()
        o.put("package", pkg)
        o.put("name", name)
        arr.put(o)
        saveBlocks(arr)
    }

    private fun removeBlock(pkg: String) {
        val arr = getBlocks()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("package") != pkg) out.put(o)
        }
        saveBlocks(out)
    }


    private fun renderAllBlocks() {
        containerBlocks.removeAllViews()
        val arr = getBlocks()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            addCardAnimated(o.optString("package"), o.optString("name"), false)
        }
    }


    private fun addCardAnimated(pkg: String, name: String, animate: Boolean = true) {
        val inflater = LayoutInflater.from(this)
        val card = inflater.inflate(R.layout.block_card_item, containerBlocks, false)
        val title = card.findViewById<TextView>(R.id.txtBlockName)
        val subtitle = card.findViewById<TextView>(R.id.txtBlockDesc)

        title.text = name
        subtitle.text = "Tap to remove (requires PIN)"


        card.setOnClickListener {
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("action", PIN_ACTION_VERIFY_REMOVE)
            i.putExtra("blocked_package", pkg)
            startActivity(i)
        }


        if (animate) {
            card.alpha = 0f
            card.translationY = 30f
        }
        containerBlocks.addView(card, 0)

        if (animate) {
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setDuration(350)
                .start()
        } else {

            card.scaleX = 0.98f
            card.scaleY = 0.98f
            card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }
    }


    override fun onResume() {
        super.onResume()

        renderAllBlocks()
    }


    private fun loadInstalledApps() {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
        installedApps.clear()
        for (ri in apps) {
            val label = ri.loadLabel(packageManager).toString()
            val pkg = ri.activityInfo.packageName
            if (pkg != packageName) installedApps.add(AppInfo(label, pkg))
        }
        installedApps.sortBy { it.name }
    }
}
