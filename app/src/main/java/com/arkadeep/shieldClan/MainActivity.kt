package com.arkadeep.shieldClan

import android.animation.LayoutTransition
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    data class AppInfo(val name: String, val packageName: String) {
        override fun toString(): String = name
    }

    private lateinit var spinnerApps: Spinner
    private lateinit var btnBlock: Button
    private lateinit var btnSetPin: Button
    private lateinit var containerBlocks: LinearLayout

    private lateinit var layoutPermissions: LinearLayout
    private lateinit var btnPermOverlay: Button
    private lateinit var btnPermAccess: Button

    private val installedApps = mutableListOf<AppInfo>()
    private val prefsName = "BlockerPrefs"

    private val PIN_ACTION_SET = "set_pin"
    private val PIN_ACTION_VERIFY_REMOVE = "verify_and_remove"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        CloudManager.initListener(this)

        spinnerApps = findViewById(R.id.spinnerApps)
        btnBlock = findViewById(R.id.btnStart)
        btnSetPin = findViewById(R.id.btnSetPin)
        containerBlocks = findViewById(R.id.containerBlocks)

        layoutPermissions = findViewById(R.id.layoutPermissions)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermAccess = findViewById(R.id.btnPermAccess)

        containerBlocks.layoutTransition = LayoutTransition()

        loadInstalledApps()
        spinnerApps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, installedApps)

        setupListeners()
        renderAllBlocks()
    }

    override fun onResume() {
        super.onResume()

        renderAllBlocks()

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (prefs.contains("pin_hash")) {
            btnSetPin.text = "Change Security PIN"
        } else {
            btnSetPin.text = "Set Security PIN"
        }

        checkAndEnforcePermissions()
    }

    private fun setupListeners() {
        btnSetPin.setOnClickListener {
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("action", PIN_ACTION_SET)
            startActivity(i)
        }

        btnBlock.setOnClickListener {
            val selected = spinnerApps.selectedItem as? AppInfo
            if (selected == null) {
                Toast.makeText(this, "Pick an app first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val salt = prefs.getString("pin_salt", null)
            val hash = prefs.getString("pin_hash", null)

            if (salt == null || hash == null) {
                Toast.makeText(this, "⚠️ Set a Security PIN first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            addInfiniteBlock(selected.packageName, selected.name)
            addCardAnimated(selected.packageName, selected.name)
            Toast.makeText(this, "Blocked ${selected.name}", Toast.LENGTH_SHORT).show()
        }

        btnPermOverlay.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        btnPermAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Shield Clan' & Turn ON", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndEnforcePermissions() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = isAccessibilityEnabled()

        if (hasOverlay && hasAccess) {
            layoutPermissions.visibility = View.GONE
        } else {
            layoutPermissions.visibility = View.VISIBLE

            if (hasOverlay) {
                btnPermOverlay.visibility = View.GONE
            } else {
                btnPermOverlay.visibility = View.VISIBLE
                btnPermOverlay.text = "1. Allow Display Over Apps"
            }

            if (hasAccess) {
                btnPermAccess.visibility = View.GONE
            } else {
                btnPermAccess.visibility = View.VISIBLE
                btnPermAccess.text = if (hasOverlay) "Enable Accessibility Service" else "2. Enable Accessibility Service"
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, BlockService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName)
                return true
        }
        return false
    }

    private fun getBlocks(): JSONArray {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return JSONArray(prefs.getString("blocks_json", "[]"))
    }

    private fun saveBlocks(arr: JSONArray) {
        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString("blocks_json", arr.toString()).apply()
        val intent = Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS")
        sendBroadcast(intent)
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
            card.alpha = 1f
            card.translationY = 0f
        }
    }

    private fun loadInstalledApps() {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
        installedApps.clear()
        for (ri in apps) {
            val label = ri.loadLabel(packageManager).toString()
            val pkg = ri.activityInfo.packageName
            if (pkg != packageName) {
                installedApps.add(AppInfo(label, pkg))
            }
        }
        installedApps.sortBy { it.name }
    }
}