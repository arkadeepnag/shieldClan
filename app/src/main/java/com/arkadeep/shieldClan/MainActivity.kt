package com.arkadeep.shieldClan

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    data class AppInfo(val name: String, val packageName: String) {
        override fun toString(): String = name
    }

    private lateinit var spinnerApps: Spinner
    private lateinit var btnBlock: View
    private lateinit var btnSetPin: TextView
    private lateinit var containerBlocks: LinearLayout
    private lateinit var txtSystemStatus: TextView
    private lateinit var imgSystemStatus: ImageView
    private lateinit var txtFocusTitle: TextView
    private lateinit var txtFocusDesc: TextView
    private lateinit var layoutPermissionsContainer: ViewGroup
    private var btnClosePerms: Button? = null

    // Permissions Variables
    private var permOverlayCheck: CheckBox? = null
    private var permAccessCheck: CheckBox? = null
    private var permNotifCheck: CheckBox? = null
    private var permAdminCheck: CheckBox? = null
    private var permOverlayCard: View? = null
    private var permAccessCard: View? = null
    private var permNotifCard: View? = null
    private var permAdminCard: View? = null

    private val installedApps = mutableListOf<AppInfo>()
    private val prefsName = "BlockerPrefs"
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val REQUEST_CODE_DEVICE_ADMIN = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        CloudManager.initListener(this)

        spinnerApps = findViewById(R.id.spinnerApps)
        btnBlock = findViewById(R.id.btnStart)
        btnSetPin = findViewById(R.id.btnSetPin)
        containerBlocks = findViewById(R.id.containerBlocks)
        txtSystemStatus = findViewById(R.id.txtSystemStatus)
        imgSystemStatus = findViewById(R.id.imgSystemStatus)
        txtFocusTitle = findViewById(R.id.txtFocusTitle)
        txtFocusDesc = findViewById(R.id.txtFocusDesc)
        layoutPermissionsContainer = findViewById(R.id.layoutPermissions)

        containerBlocks.layoutTransition = LayoutTransition()

        setupPermissionSheet()
        loadInstalledApps()
        setupListeners()
        renderAllBlocks()

        // --- FIX 1: FORCE BLOCK SETTINGS ON STARTUP ---
        ensureSettingsBlocked()
        // ----------------------------------------------
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndRefreshUI()
        renderAllBlocks()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun ensureSettingsBlocked() {
        // If PIN is set, verify Settings is blocked
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (prefs.contains("pin_code")) {
            addBlock("com.android.settings", "Settings")
        }
    }

    // --- PERMISSION LOGIC ---
    private fun setupPermissionSheet() {
        val sheet = LayoutInflater.from(this).inflate(R.layout.layout_permission_sheet, layoutPermissionsContainer, true)
        permOverlayCheck = sheet.findViewById(R.id.checkOverlay)
        permAccessCheck = sheet.findViewById(R.id.checkAccess)
        permNotifCheck = sheet.findViewById(R.id.checkNotif)
        permAdminCheck = sheet.findViewById(R.id.checkAdmin)
        permOverlayCard = sheet.findViewById(R.id.cardPermOverlay)
        permAccessCard = sheet.findViewById(R.id.cardPermAccess)
        permNotifCard = sheet.findViewById(R.id.cardPermNotif)
        permAdminCard = sheet.findViewById(R.id.cardPermAdmin)
        btnClosePerms = sheet.findViewById(R.id.btnClosePerms)

        permOverlayCard?.setOnClickListener { if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        permAccessCard?.setOnClickListener { if (!isAccessibilityEnabled()) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); Toast.makeText(this, "Find Shield Clan and turn it ON", Toast.LENGTH_LONG).show() } }
        permNotifCard?.setOnClickListener { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101) }
        permAdminCard?.setOnClickListener { requestDeviceAdmin() }
        btnClosePerms?.setOnClickListener { layoutPermissionsContainer.visibility = View.GONE }
    }

    private fun checkPermissionsAndRefreshUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = isAccessibilityEnabled()
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val hasAdmin = dpm.isAdminActive(ComponentName(this, ShieldDeviceAdmin::class.java))

        permOverlayCheck?.isChecked = hasOverlay; permAccessCheck?.isChecked = hasAccess; permNotifCheck?.isChecked = hasNotif; permAdminCheck?.isChecked = hasAdmin
        permOverlayCard?.alpha = if (hasOverlay) 0.5f else 1f; permAccessCard?.alpha = if (hasAccess) 0.5f else 1f; permNotifCard?.alpha = if (hasNotif) 0.5f else 1f; permAdminCard?.alpha = if (hasAdmin) 0.5f else 1f

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) permNotifCard?.visibility = View.GONE
        val allGranted = hasOverlay && hasAccess && hasNotif && hasAdmin
        layoutPermissionsContainer.visibility = if (allGranted) View.GONE else View.VISIBLE
        btnClosePerms?.visibility = if (allGranted) View.VISIBLE else View.GONE
        updateFocusUI(allGranted)
    }

    // --- MAIN LOGIC ---
    private fun setupListeners() {
        btnSetPin.setOnClickListener { startActivity(Intent(this, PinActivity::class.java).putExtra("action", "set_pin")) }
        spinnerApps.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && !getSharedPreferences(prefsName, Context.MODE_PRIVATE).contains("pin_code")) {
                Toast.makeText(this, "⚠️ Set a Security PIN to see your apps!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, PinActivity::class.java).putExtra("action", "set_pin"))
                return@setOnTouchListener true
            }
            false
        }
        btnBlock.setOnClickListener {
            if (layoutPermissionsContainer.visibility == View.VISIBLE) { Toast.makeText(this, "Grant permissions first!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!getSharedPreferences(prefsName, Context.MODE_PRIVATE).contains("pin_code")) {
                Toast.makeText(this, "⚠️ You must set a Security PIN first!", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, PinActivity::class.java).putExtra("action", "set_pin"))
                return@setOnClickListener
            }
            val app = spinnerApps.selectedItem as? AppInfo ?: return@setOnClickListener
            addBlock(app.packageName, app.name)
        }
    }

    private fun loadInstalledApps() {
        scope.launch(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(intent, 0)
            val tempApps = mutableListOf<AppInfo>()
            for (ri in apps) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || pkg == "com.arkadeep.shieldClan") continue
                if ((ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue
                tempApps.add(AppInfo(ri.loadLabel(packageManager).toString(), pkg))
            }
            tempApps.sortBy { it.name }
            withContext(Dispatchers.Main) {
                installedApps.clear(); installedApps.addAll(tempApps)
                spinnerApps.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, installedApps)
            }
        }
    }

    private fun addBlock(pkg: String, name: String) {
        val arr = getBlocks()
        var exists = false
        for (i in 0 until arr.length()) { if (arr.getJSONObject(i).optString("package") == pkg) exists = true }

        if (!exists) {
            arr.put(JSONObject().apply { put("package", pkg); put("name", name) })
            saveBlocks(arr)
            renderAllBlocks()
            updateFocusUI(true)

            // If user blocks ANYTHING, ensure settings is also blocked
            if (pkg != "com.android.settings") {
                addBlock("com.android.settings", "Settings")
            }
        }


    }



    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, ShieldDeviceAdmin::class.java)
        if (!dpm.isAdminActive(componentName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN)
        } else {
            checkPermissionsAndRefreshUI()
        }
    }

    private fun updateFocusUI(permissionsGranted: Boolean) {
        val count = getBlocks().length()
        if (permissionsGranted && count > 0) {
            txtSystemStatus.text = "SYSTEM ACTIVE"; imgSystemStatus.setImageResource(android.R.drawable.presence_online)
            txtFocusTitle.text = "You are focused."; txtFocusDesc.text = "$count app(s) restricted."
        } else {
            txtSystemStatus.text = "SYSTEM IDLE"; imgSystemStatus.setImageResource(android.R.drawable.presence_invisible)
            txtFocusTitle.text = if (!permissionsGranted) "Setup Required" else "Shield Inactive"
            txtFocusDesc.text = if (!permissionsGranted) "Grant permissions." else "Add an app to start."
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, BlockService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':'); splitter.setString(enabled)
        while (splitter.hasNext()) { if (ComponentName.unflattenFromString(splitter.next()) == expected) return true }
        return false
    }

    private fun getBlocks(): JSONArray = JSONArray(getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("blocks_json", "[]"))

    private fun saveBlocks(arr: JSONArray) {
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("blocks_json", arr.toString()).apply()
        sendBroadcast(Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS"))
    }

    private fun renderAllBlocks() {
        containerBlocks.removeAllViews()
        val arr = getBlocks()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            addCardAnimated(o.getString("package"), o.getString("name"), false)
        }
    }

    private fun addCardAnimated(pkg: String, name: String, animate: Boolean) {
        val card = LayoutInflater.from(this).inflate(R.layout.block_card_item, containerBlocks, false)
        card.findViewById<TextView>(R.id.txtBlockName).text = name
        card.findViewById<TextView>(R.id.txtBlockDesc).text = "Tap to remove (requires PIN)"
        try { card.findViewById<ImageView>(R.id.imgAppIcon).setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (e: Exception) { card.findViewById<ImageView>(R.id.imgAppIcon).setImageResource(android.R.drawable.sym_def_app_icon) }
        card.setOnClickListener { startActivity(Intent(this, PinActivity::class.java).putExtra("blocked_package", pkg).putExtra("action", "verify_and_remove")) }
        containerBlocks.addView(card, 0)
        if (animate) { card.alpha = 0f; card.translationY = 50f; card.animate().alpha(1f).translationY(0f).setDuration(400).setInterpolator(AccelerateDecelerateInterpolator()).start() }
    }
}