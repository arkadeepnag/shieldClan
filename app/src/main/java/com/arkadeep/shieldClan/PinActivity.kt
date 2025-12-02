package com.arkadeep.shieldClan

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.*

class PinActivity : Activity() {

    private val prefsName = "BlockerPrefs"
    private val PIN_HASH = "pin_hash"
    private val PIN_SALT = "pin_salt"
    private val ATTEMPTS_KEY = "pin_attempts"
    private val LOCKOUT_KEY = "pin_lockout_until"

    private val MAX_ATTEMPTS = 3
    private val LOCKOUT_MS = 5 * 60 * 1000L // 5 minutes

    private lateinit var etPin: EditText
    private lateinit var btnAction: Button
    private lateinit var tvTitle: TextView // Suggest adding a Title TextView in XML if possible, or use Toast

    // State management for the PIN process
    private enum class PinStep {
        VERIFY_TO_UNLOCK, // Unlocking an app
        VERIFY_TO_CHANGE, // Verifying old pin before changing
        SET_NEW           // Setting a fresh pin
    }

    private var currentStep = PinStep.SET_NEW
    private var blockedPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        etPin = findViewById(R.id.etPin)
        val btnSave = findViewById<Button>(R.id.btnSavePin)
        val btnVerify = findViewById<Button>(R.id.btnVerifyPin)

        // Combine buttons for cleaner logic (Use one button, change text)
        btnAction = if (btnSave.visibility == Button.VISIBLE) btnSave else btnVerify
        // Ensure one button is always visible for this logic, or just use btnVerify in XML and rename it to btnAction
        btnSave.visibility = Button.GONE
        btnVerify.visibility = Button.VISIBLE
        btnAction = btnVerify

        val action = intent.getStringExtra("action")
        blockedPackage = intent.getStringExtra("blocked_package")

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val hasPin = prefs.contains(PIN_HASH)

        // --- DETERMINE STATE ---
        if (action == "set_pin") {
            if (hasPin) {
                currentStep = PinStep.VERIFY_TO_CHANGE
                btnAction.text = "Verify Old PIN"
                Toast.makeText(this, "Enter OLD PIN to continue", Toast.LENGTH_SHORT).show()
            } else {
                currentStep = PinStep.SET_NEW
                btnAction.text = "Set New PIN"
            }
        } else {
            // "verify_and_remove" or any unlock attempt
            currentStep = PinStep.VERIFY_TO_UNLOCK
            btnAction.text = "Unlock"
        }

        btnAction.setOnClickListener {
            handlePinAction()
        }
    }

    private fun handlePinAction() {
        val inputPin = etPin.text.toString().trim()
        if (inputPin.length != 6) {
            Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // CHECK LOCKOUT
        val now = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(LOCKOUT_KEY, 0L)
        if (now < lockoutUntil) {
            val remainS = (lockoutUntil - now) / 1000
            Toast.makeText(this, "Locked. Wait ${remainS}s", Toast.LENGTH_SHORT).show()
            return
        }

        when (currentStep) {
            PinStep.SET_NEW -> {
                saveNewPin(inputPin, prefs)
            }
            PinStep.VERIFY_TO_UNLOCK, PinStep.VERIFY_TO_CHANGE -> {
                verifyPin(inputPin, prefs)
            }
        }
    }

    private fun verifyPin(inputPin: String, prefs: android.content.SharedPreferences) {
        val storedHash = prefs.getString(PIN_HASH, "")
        val storedSalt = prefs.getString(PIN_SALT, "")

        if (storedHash.isNullOrEmpty() || storedSalt.isNullOrEmpty()) {
            // Should not happen in verify mode, but if it does, switch to set
            currentStep = PinStep.SET_NEW
            btnAction.text = "Set New PIN"
            return
        }

        val inputHash = hashPin(inputPin, storedSalt)

        if (inputHash == storedHash) {
            // SUCCESS
            prefs.edit().putInt(ATTEMPTS_KEY, 0).remove(LOCKOUT_KEY).apply()

            if (currentStep == PinStep.VERIFY_TO_CHANGE) {
                // Phase 1 Complete: Old PIN verified. Now allow setting new.
                currentStep = PinStep.SET_NEW
                etPin.text.clear()
                btnAction.text = "Save New PIN"
                Toast.makeText(this, "Identity Verified. Enter NEW PIN.", Toast.LENGTH_SHORT).show()
            } else {
                // Unlock Action
                if (blockedPackage != null) {
                    removeBlock(blockedPackage!!)
                    finish() // Close screen
                } else {
                    Toast.makeText(this, "Verified", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } else {
            // FAILURE
            handleFailedAttempt(prefs)
        }
    }

    private fun saveNewPin(pin: String, prefs: android.content.SharedPreferences) {
        val newSalt = UUID.randomUUID().toString()
        val newHash = hashPin(pin, newSalt)

        prefs.edit()
            .putString(PIN_SALT, newSalt)
            .putString(PIN_HASH, newHash)
            .putInt(ATTEMPTS_KEY, 0)
            .remove(LOCKOUT_KEY)
            .apply()

        Toast.makeText(this, "PIN Updated Successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handleFailedAttempt(prefs: android.content.SharedPreferences) {
        var attempts = prefs.getInt(ATTEMPTS_KEY, 0)
        attempts++
        val edit = prefs.edit().putInt(ATTEMPTS_KEY, attempts)

        if (attempts >= MAX_ATTEMPTS) {
            val lockUntil = System.currentTimeMillis() + LOCKOUT_MS
            edit.putLong(LOCKOUT_KEY, lockUntil)
            Toast.makeText(this, "Too many attempts. Locked for 5 mins.", Toast.LENGTH_LONG).show()
        } else {
            val left = MAX_ATTEMPTS - attempts
            Toast.makeText(this, "Wrong PIN. Attempts left: $left", Toast.LENGTH_SHORT).show()
        }
        edit.apply()
        etPin.text.clear()
    }

    private fun removeBlock(pkg: String) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("blocks_json", "[]"))
        val out = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("package") != pkg) out.put(o)
        }
        prefs.edit().putString("blocks_json", out.toString()).apply()
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest((pin + salt).toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}