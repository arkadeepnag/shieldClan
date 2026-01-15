package com.arkadeep.shieldClan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import java.util.Calendar

class PinActivity : Activity() {

    private val PREFS_NAME = "BlockerPrefs"

    // Lockout Config
    private val ATTEMPTS_KEY = "pin_attempts"
    private val LOCKOUT_KEY = "pin_lockout_until"
    private val MAX_ATTEMPTS = 3
    private val LOCKOUT_MS = 5 * 60 * 1000L

    private lateinit var etPin: EditText
    private lateinit var btnVerify: Button
    private lateinit var btnSave: Button
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView

    private enum class Mode { EMERGENCY_UNLOCK, SET_NEW, VERIFY_OLD_TO_CHANGE, VERIFY_AND_REMOVE }
    private var currentMode = Mode.SET_NEW
    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        CloudManager.initListener(this)

        etPin = findViewById(R.id.etPin)
        btnVerify = findViewById(R.id.btnVerifyPin)
        btnSave = findViewById(R.id.btnSavePin)
        tvTitle = findViewById(R.id.tvShield) ?: TextView(this)
        tvSub = findViewById(R.id.tvSub) ?: TextView(this)

        val action = intent.getStringExtra("action")
        targetPackage = intent.getStringExtra("blocked_package")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasLocalPin = prefs.contains("pin_code")

        if (action == "set_pin") {
            if (hasLocalPin) {
                currentMode = Mode.VERIFY_OLD_TO_CHANGE
                setupUI(verify = true, "Verify Old PIN")
            } else {
                currentMode = Mode.SET_NEW
                setupUI(verify = false, "Set New PIN")
            }
        }
        else if (action == "verify_and_remove") {
            currentMode = Mode.VERIFY_AND_REMOVE
            setupUI(verify = true, "Verify to Delete Block")
        }
        else if (action == "emergency_unlock" && targetPackage != null) {
            val isSettings = targetPackage == "com.android.settings"
            if (!isSettings && tryFreeUnlock(prefs)) return

            if (hasLocalPin) {
                currentMode = Mode.EMERGENCY_UNLOCK
                setupUI(verify = true, "Quota Used. Enter PIN.")
            } else {
                Toast.makeText(this, "No PIN set. Unlocking...", Toast.LENGTH_SHORT).show()
                grantTemporaryAccess(targetPackage!!)
            }
        }
    }

    private fun validatePin() {
        val input = etPin.text.toString().trim()
        if (input.length != 6) {
            Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(LOCKOUT_KEY, 0L)
        if (now < lockoutUntil) {
            val remainS = (lockoutUntil - now) / 1000
            Toast.makeText(this, "Locked. Wait ${remainS}s", Toast.LENGTH_SHORT).show()
            etPin.text.clear()
            return
        }

        btnVerify.isEnabled = false
        btnVerify.text = "Verifying with Cloud..."

        val deviceId = CloudManager.getDeviceId(this)
        val dbRef = FirebaseDatabase.getInstance().getReference("devices")
            .child(deviceId).child("pin_code")

        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cloudPin = snapshot.value?.toString()

                if (cloudPin.isNullOrEmpty()) {
                    Toast.makeText(this@PinActivity, "No PIN found on Cloud", Toast.LENGTH_SHORT).show()
                    finish()
                } else if (input == cloudPin) {
                    onPinSuccess(prefs)
                } else {
                    onPinFailure(prefs)
                }
                btnVerify.isEnabled = true
                updateButtonText()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@PinActivity, "Network Error", Toast.LENGTH_SHORT).show()
                btnVerify.isEnabled = true
                updateButtonText()
            }
        })
    }

    private fun updateButtonText() {
        when (currentMode) {
            Mode.VERIFY_OLD_TO_CHANGE -> btnVerify.text = "Verify Old PIN"
            Mode.VERIFY_AND_REMOVE -> btnVerify.text = "Verify to Delete Block"
            Mode.EMERGENCY_UNLOCK -> btnVerify.text = "Quota Used. Enter PIN."
            else -> {}
        }
    }

    private fun onPinSuccess(prefs: SharedPreferences) {
        prefs.edit().putInt(ATTEMPTS_KEY, 0).remove(LOCKOUT_KEY).apply()
        prefs.edit().putString("pin_code", etPin.text.toString()).apply()

        when (currentMode) {
            Mode.VERIFY_OLD_TO_CHANGE -> {
                currentMode = Mode.SET_NEW
                setupUI(verify = false, "Save NEW PIN")
                Toast.makeText(this, "Verified. Enter new PIN.", Toast.LENGTH_SHORT).show()
            }
            Mode.VERIFY_AND_REMOVE -> {
                if (targetPackage != null) removeBlock(targetPackage!!)
                finish()
            }
            Mode.EMERGENCY_UNLOCK -> {
                if (targetPackage != null) grantTemporaryAccess(targetPackage!!)
            }
            else -> {}
        }
    }

    private fun onPinFailure(prefs: SharedPreferences) {
        var attempts = prefs.getInt(ATTEMPTS_KEY, 0)
        attempts++
        val edit = prefs.edit().putInt(ATTEMPTS_KEY, attempts)

        if (attempts >= MAX_ATTEMPTS) {
            val lockUntil = System.currentTimeMillis() + LOCKOUT_MS
            edit.putLong(LOCKOUT_KEY, lockUntil)
            Toast.makeText(this, "Locked for 5 mins.", Toast.LENGTH_LONG).show()
            etPin.text.clear()
        } else {
            val left = MAX_ATTEMPTS - attempts
            Toast.makeText(this, "Wrong PIN. Attempts left: $left", Toast.LENGTH_SHORT).show()
            etPin.error = "Wrong PIN"
        }
        edit.apply()
    }

    private fun saveNewPin() {
        val input = etPin.text.toString().trim()
        if (input.length != 6) {
            Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("pin_code", input).putInt(ATTEMPTS_KEY, 0).remove(LOCKOUT_KEY).apply()
        CloudManager.uploadPinConfig(this, input)
        Toast.makeText(this, "PIN Saved & Synced", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun tryFreeUnlock(prefs: SharedPreferences): Boolean {
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastDay = prefs.getInt("free_unlock_day", -1)
        var count = prefs.getInt("free_unlock_count", 0)

        if (currentDay != lastDay) {
            count = 0
            prefs.edit().putInt("free_unlock_day", currentDay).putInt("free_unlock_count", 0).apply()
        }

        if (count >= 2) return false

        prefs.edit()
            .putInt("free_unlock_count", count + 1)
            .putLong("free_unlock_time", System.currentTimeMillis())
            .apply()

        Toast.makeText(this, "Free Emergency Access (${count + 1}/2)", Toast.LENGTH_LONG).show()
        grantTemporaryAccess(targetPackage!!)
        return true
    }

    private fun grantTemporaryAccess(pkg: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // --- ROLLOVER LOGIC ---
        val savedCredit = prefs.getLong("saved_time_credit", 0L)
        val standardTime = 3 * 60 * 1000L
        val totalDuration = standardTime + savedCredit

        // If we used credit, notify and reset it
        if (savedCredit > 0) {
            val seconds = savedCredit / 1000
            Toast.makeText(this, "Rollover Time Added: +${seconds}s", Toast.LENGTH_LONG).show()
            prefs.edit().putLong("saved_time_credit", 0L).apply()
        }
        // ----------------------

        val expiry = System.currentTimeMillis() + totalDuration
        prefs.edit().putLong("temp_unlock_$pkg", expiry).apply()

        val intent = Intent("com.arkadeep.shieldClan.START_TIMER")
        intent.putExtra("duration", totalDuration)
        intent.putExtra("package", pkg)
        sendBroadcast(intent)
        sendBroadcast(Intent("com.arkadeep.shieldClan.CLOSE_BLOCK_SCREEN"))
        finish()
    }

    private fun removeBlock(pkg: String) {
        if (pkg == "com.android.settings") {
            Toast.makeText(this, "⛔ Settings cannot be unblocked.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("blocks_json", "[]"))
        val out = JSONArray()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("package") != pkg) out.put(o)
        }

        prefs.edit().putString("blocks_json", out.toString()).apply()
        sendBroadcast(Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS"))
        sendBroadcast(Intent("com.arkadeep.shieldClan.CLOSE_BLOCK_SCREEN"))
        Toast.makeText(this, "Block Removed", Toast.LENGTH_SHORT).show()
    }

    private fun setupUI(verify: Boolean, btnText: String) {
        etPin.text.clear()
        if (verify) {
            btnVerify.visibility = View.VISIBLE
            btnSave.visibility = View.GONE
            btnVerify.text = btnText
            btnVerify.setOnClickListener { validatePin() }
        } else {
            btnVerify.visibility = View.GONE
            btnSave.visibility = View.VISIBLE
            btnSave.text = btnText
            btnSave.setOnClickListener { saveNewPin() }
        }
    }
}