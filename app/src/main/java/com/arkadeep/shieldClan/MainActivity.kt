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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    // Data class for Spinner
    data class AppInfo(val name: String, val packageName: String) {
        override fun toString(): String = name
    }

    // UI Components
    private lateinit var spinnerApps: Spinner
    private lateinit var btnBlock: View
    private lateinit var btnSetPin: TextView
    private lateinit var containerBlocks: LinearLayout
    private lateinit var txtSystemStatus: TextView
    private lateinit var imgSystemStatus: ImageView
    private lateinit var txtFocusTitle: TextView
    private lateinit var txtFocusDesc: TextView
    private lateinit var layoutPermissionsContainer: ViewGroup

    // Permission Sheet UI
    private var permOverlayCheck: CheckBox? = null
    private var permAccessCheck: CheckBox? = null
    private var permNotifCheck: CheckBox? = null
    private var permAdminCheck: CheckBox? = null // Device Admin

    private var permOverlayCard: View? = null
    private var permAccessCard: View? = null
    private var permNotifCard: View? = null
    private var permAdminCard: View? = null // Device Admin

    private var btnClosePerms: Button? = null

    private val installedApps = mutableListOf<AppInfo>()
    private val prefsName = "BlockerPrefs"
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Request Code
    private val REQUEST_CODE_DEVICE_ADMIN = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        // --- 1. INITIALIZE CLOUD LISTENER ---
        CloudManager.initListener(this)
        // ------------------------------------

        // Bind Views
        spinnerApps = findViewById(R.id.spinnerApps)
        btnBlock = findViewById(R.id.btnStart)
        btnSetPin = findViewById(R.id.btnSetPin)
        containerBlocks = findViewById(R.id.containerBlocks)
        txtSystemStatus = findViewById(R.id.txtSystemStatus)
        imgSystemStatus = findViewById(R.id.imgSystemStatus)
        txtFocusTitle = findViewById(R.id.txtFocusTitle)
        txtFocusDesc = findViewById(R.id.txtFocusDesc)
        layoutPermissionsContainer = findViewById(R.id.layoutPermissions)

        // Enable Layout Animations
        containerBlocks.layoutTransition = LayoutTransition()

        setupPermissionSheet()
        loadInstalledApps()
        setupListeners()
        renderAllBlocks()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndRefreshUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // --- PERMISSION LOGIC ---

    private fun setupPermissionSheet() {
        val sheet = LayoutInflater.from(this)
            .inflate(R.layout.layout_permission_sheet, layoutPermissionsContainer, true)

        // Bind Checkboxes
        permOverlayCheck = sheet.findViewById(R.id.checkOverlay)
        permAccessCheck = sheet.findViewById(R.id.checkAccess)
        permNotifCheck = sheet.findViewById(R.id.checkNotif)
        permAdminCheck = sheet.findViewById(R.id.checkAdmin)

        // Bind Cards
        permOverlayCard = sheet.findViewById(R.id.cardPermOverlay)
        permAccessCard = sheet.findViewById(R.id.cardPermAccess)
        permNotifCard = sheet.findViewById(R.id.cardPermNotif)
        permAdminCard = sheet.findViewById(R.id.cardPermAdmin)

        btnClosePerms = sheet.findViewById(R.id.btnClosePerms)

        // Listeners
        permOverlayCard?.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        permAccessCard?.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Find Shield Clan and turn it ON", Toast.LENGTH_LONG).show()
            }
        }

        permNotifCard?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        permAdminCard?.setOnClickListener {
            requestDeviceAdmin()
        }

        btnClosePerms?.setOnClickListener {
            layoutPermissionsContainer.visibility = View.GONE
        }
    }

    private fun checkPermissionsAndRefreshUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = isAccessibilityEnabled()

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, ShieldDeviceAdmin::class.java)
        val hasAdmin = dpm.isAdminActive(adminComponent)

        // Update UI
        permOverlayCheck?.isChecked = hasOverlay
        permAccessCheck?.isChecked = hasAccess
        permNotifCheck?.isChecked = hasNotif
        permAdminCheck?.isChecked = hasAdmin

        permOverlayCard?.alpha = if (hasOverlay) 0.5f else 1.0f
        permAccessCard?.alpha = if (hasAccess) 0.5f else 1.0f
        permNotifCard?.alpha = if (hasNotif) 0.5f else 1.0f
        permAdminCard?.alpha = if (hasAdmin) 0.5f else 1.0f

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permNotifCard?.visibility = View.GONE
        }

        val allGranted = hasOverlay && hasAccess && hasNotif && hasAdmin

        if (allGranted) {
            layoutPermissionsContainer.visibility = View.GONE
        } else {
            layoutPermissionsContainer.visibility = View.VISIBLE
            btnClosePerms?.visibility = if (allGranted) View.VISIBLE else View.GONE
        }

        updateFocusUI(allGranted)
    }

    // --- MAIN APP LOGIC ---

    private fun setupListeners() {
        btnSetPin.setOnClickListener {
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("action", "set_pin")
            startActivity(i)
        }

        // LOCK SPINNER IF NO PIN
        spinnerApps.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                if (!prefs.contains("pin_hash")) {
                    Toast.makeText(this, "⚠️ Set a Security PIN to see your apps!", Toast.LENGTH_SHORT).show()
                    val i = Intent(this, PinActivity::class.java)
                    i.putExtra("action", "set_pin")
                    startActivity(i)
                    return@setOnTouchListener true
                }
            }
            false
        }

        btnBlock.setOnClickListener {
            if (layoutPermissionsContainer.visibility == View.VISIBLE) {
                Toast.makeText(this, "Grant permissions first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            if (!prefs.contains("pin_hash")) {
                Toast.makeText(this, "⚠️ You must set a Security PIN first!", Toast.LENGTH_LONG).show()
                val i = Intent(this, PinActivity::class.java)
                i.putExtra("action", "set_pin")
                startActivity(i)
                return@setOnClickListener
            }

            val app = spinnerApps.selectedItem as? AppInfo
            if (app == null) return@setOnClickListener

            addBlock(app.packageName, app.name)
        }
    }

    private fun loadInstalledApps() {
        scope.launch(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)

            val apps = packageManager.queryIntentActivities(intent, 0)
            val tempApps = mutableListOf<AppInfo>()

            for (ri in apps) {
                val pkg = ri.activityInfo.packageName

                // Skip Self
                if (pkg == packageName || pkg == "com.arkadeep.shieldClan") continue

                // FILTER: Ignore System Apps (unless updated)
                val isSystemApp = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                if (isSystemApp && !isUpdatedSystemApp) {
                    continue
                }

                val name = ri.loadLabel(packageManager).toString()
                tempApps.add(AppInfo(name, pkg))
            }

            tempApps.sortBy { it.name }

            withContext(Dispatchers.Main) {
                installedApps.clear()
                installedApps.addAll(tempApps)
                spinnerApps.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    installedApps
                )
            }
        }
    }

    // --- BLOCKING & STEALTH ---

    private fun addBlock(pkg: String, name: String) {
        val arr = getBlocks()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("package") == pkg) return
        }

        arr.put(JSONObject().apply {
            put("package", pkg)
            put("name", name)
        })
        saveBlocks(arr)

        renderAllBlocks()
        updateFocusUI(true)

        // Auto-Block Settings
        if (pkg != "com.android.settings") {
            addBlock("com.android.settings", "Settings")
        }
    }


    private fun requestDeviceAdmin() {
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, ShieldDeviceAdmin::class.java)

        if (!devicePolicyManager.isAdminActive(componentName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Activating this prevents the app from being uninstalled easily. You must deactivate this to uninstall.")
            startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN)
        } else {
            Toast.makeText(this, "Already Active", Toast.LENGTH_SHORT).show()
            checkPermissionsAndRefreshUI()
        }
    }

    // --- HELPERS ---

    private fun updateFocusUI(permissionsGranted: Boolean) {
        val count = getBlocks().length()
        val systemActive = permissionsGranted && count > 0

        if (systemActive) {
            txtSystemStatus.text = "SYSTEM ACTIVE"
            imgSystemStatus.setImageResource(android.R.drawable.presence_online)
            txtFocusTitle.text = "You are focused."
            txtFocusDesc.text = "$count app${if (count > 1) "s are" else " is"} currently restricted."
        } else {
            txtSystemStatus.text = "SYSTEM IDLE"
            imgSystemStatus.setImageResource(android.R.drawable.presence_invisible)
            if (!permissionsGranted) {
                txtFocusTitle.text = "Setup Required"
                txtFocusDesc.text = "Grant all permissions to activate Shield."
            } else {
                txtFocusTitle.text = "Shield Inactive"
                txtFocusDesc.text = "Add an app above to start blocking."
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, BlockService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName != null && componentName == expected) return true
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

    private fun addCardAnimated(pkg: String, name: String, animate: Boolean = true) {
        val card = LayoutInflater.from(this).inflate(R.layout.block_card_item, containerBlocks, false)
        val txtName = card.findViewById<TextView>(R.id.txtBlockName)
        val txtDesc = card.findViewById<TextView>(R.id.txtBlockDesc)
        val imgIcon = card.findViewById<ImageView>(R.id.imgAppIcon)

        if (txtName != null) txtName.text = name
        if (txtDesc != null) txtDesc.text = "Tap to remove (requires PIN)"

        try {
            val iconDrawable = packageManager.getApplicationIcon(pkg)
            imgIcon?.setImageDrawable(iconDrawable)
        } catch (e: Exception) {
            imgIcon?.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        card.setOnClickListener {
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("blocked_package", pkg)
            i.putExtra("action", "verify_and_remove")
            startActivity(i)
        }

        containerBlocks.addView(card, 0)
        if (animate) {
            card.alpha = 0f
            card.translationY = 50f
            card.animate().alpha(1f).translationY(0f).setDuration(400).setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }
}