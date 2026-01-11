package com.arkadeep.shieldClan

import android.animation.LayoutTransition
import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    // Simple Data class
    data class AppInfo(val name: String, val packageName: String) {
        override fun toString(): String = name
    }

    // UI Components
    private lateinit var spinnerApps: Spinner
    private lateinit var btnBlock: View // Can be Button or AppCompatButton
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
    private var permOverlayCard: View? = null
    private var permAccessCard: View? = null
    private var btnClosePerms: Button? = null

    private val installedApps = mutableListOf<AppInfo>()
    private val prefsName = "BlockerPrefs"

    // Coroutine Scope for background tasks
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        // Enable animations for adding/removing blocks
        containerBlocks.layoutTransition = LayoutTransition()

        // Inflate the permission sheet into the empty FrameLayout
        setupPermissionSheet()

        // Load apps in background to prevent UI freeze
        loadInstalledApps()

        setupListeners()
        renderAllBlocks()
    }

    override fun onResume() {
        super.onResume()
        // Critical: Check permissions and update UI every time user returns to app
        checkPermissionsAndRefreshUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // --- SETUP & LOGIC ---

    private fun setupPermissionSheet() {
        // Inflate the separate layout into the container
        val sheet = LayoutInflater.from(this)
            .inflate(R.layout.layout_permission_sheet, layoutPermissionsContainer, true)

        // Bind Sheet Views
        permOverlayCheck = sheet.findViewById(R.id.checkOverlay)
        permAccessCheck = sheet.findViewById(R.id.checkAccess)
        permOverlayCard = sheet.findViewById(R.id.cardPermOverlay)
        permAccessCard = sheet.findViewById(R.id.cardPermAccess)
        btnClosePerms = sheet.findViewById(R.id.btnClosePerms)

        // Listeners for Permission Cards
        permOverlayCard?.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }

        permAccessCard?.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find Shield Clan and turn it ON", Toast.LENGTH_LONG).show()
            }
        }

        btnClosePerms?.setOnClickListener {
            layoutPermissionsContainer.visibility = View.GONE
        }
    }

    private fun checkPermissionsAndRefreshUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = isAccessibilityEnabled()
        val allGranted = hasOverlay && hasAccess

        // Update Permission Sheet UI
        permOverlayCheck?.isChecked = hasOverlay
        permAccessCheck?.isChecked = hasAccess
        permOverlayCard?.alpha = if (hasOverlay) 0.5f else 1.0f
        permAccessCard?.alpha = if (hasAccess) 0.5f else 1.0f

        // Show/Hide Permission Sheet
        if (allGranted) {
            layoutPermissionsContainer.visibility = View.GONE
        } else {
            layoutPermissionsContainer.visibility = View.VISIBLE
            btnClosePerms?.visibility = if (allGranted) View.VISIBLE else View.GONE
        }

        updateFocusUI(allGranted)
    }

    private fun setupListeners() {
        btnSetPin.setOnClickListener {
            // Updated logic: Just open PinActivity.
            // The PinActivity logic (which we fixed) will handle "Set New" vs "Manage" automatically.
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("action", "set_pin")
            startActivity(i)
        }

        btnBlock.setOnClickListener {
            // Check if permissions are granted before blocking
            if (layoutPermissionsContainer.visibility == View.VISIBLE) {
                Toast.makeText(this, "Grant permissions first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val app = spinnerApps.selectedItem as? AppInfo
            if (app == null) return@setOnClickListener

            addBlock(app.packageName, app.name)
            addCardAnimated(app.packageName, app.name)
            updateFocusUI(true)
        }
    }

    private fun updateFocusUI(permissionsGranted: Boolean) {
        val count = getBlocks().length()
        val systemActive = permissionsGranted && count > 0

        if (systemActive) {
            // Active State
            txtSystemStatus.text = "SYSTEM ACTIVE"
            imgSystemStatus.setImageResource(android.R.drawable.presence_online)
            txtFocusTitle.text = "You are focused."
            txtFocusDesc.text = "$count app${if (count > 1) "s are" else " is"} currently restricted."
        } else {
            // Idle State
            txtSystemStatus.text = "SYSTEM IDLE"
            imgSystemStatus.setImageResource(android.R.drawable.presence_invisible) // or any gray dot

            if (!permissionsGranted) {
                txtFocusTitle.text = "Permissions Needed"
                txtFocusDesc.text = "System cannot block apps without access."
            } else {
                txtFocusTitle.text = "Shield Inactive"
                txtFocusDesc.text = "Add an app above to start blocking."
            }
        }
    }

    // --- DATA HANDLING ---

    private fun loadInstalledApps() {
        scope.launch(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)

            // Query all apps
            val apps = packageManager.queryIntentActivities(intent, 0)

            val tempApps = mutableListOf<AppInfo>()
            for (ri in apps) {
                val pkg = ri.activityInfo.packageName
                // Don't list ourselves
                if (pkg != packageName) {
                    val name = ri.loadLabel(packageManager).toString()
                    tempApps.add(AppInfo(name, pkg))
                }
            }
            tempApps.sortBy { it.name }

            // Update UI on Main Thread
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

    // --- HELPER FUNCTIONS ---

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, BlockService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName != null && componentName == expected)
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

        // CRITICAL FIX: Notify BlockService to refresh its cache immediately
        sendBroadcast(Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS"))
    }

    private fun addBlock(pkg: String, name: String) {
        val arr = getBlocks()
        // Prevent duplicates
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("package") == pkg) return
        }

        arr.put(JSONObject().apply {
            put("package", pkg)
            put("name", name)
        })
        saveBlocks(arr)
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
        val card = LayoutInflater.from(this)
            .inflate(R.layout.block_card_item, containerBlocks, false)

        val txtName = card.findViewById<TextView>(R.id.txtBlockName)
        val txtDesc = card.findViewById<TextView>(R.id.txtBlockDesc)
        val imgIcon = card.findViewById<ImageView>(R.id.imgAppIcon) // FIND THE IMAGE VIEW

        if (txtName != null) txtName.text = name
        // Fallback if ID doesn't exist in block_card_item
        else (card as? TextView)?.text = name

        if (txtDesc != null) txtDesc.text = "Tap to remove (requires PIN)"

        // --- NEW CODE: LOAD APP ICON ---
        try {
            val iconDrawable = packageManager.getApplicationIcon(pkg)
            imgIcon?.setImageDrawable(iconDrawable)
        } catch (e: Exception) {
            // App might be uninstalled, or error fetching icon.
            // The default src in XML will show (or you can set a default here)
            imgIcon?.setImageResource(android.R.drawable.sym_def_app_icon)
        }
        // -------------------------------

        card.setOnClickListener {
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("blocked_package", pkg)
            // We set action to "verify_and_remove" to trigger the unlock flow
            i.putExtra("action", "verify_and_remove")
            startActivity(i)
        }

        // Add to top of list
        containerBlocks.addView(card, 0)

        if (animate) {
            card.alpha = 0f
            card.translationY = 50f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }
}