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
import java.security.MessageDigest
import java.util.Calendar
import java.util.UUID

class PinActivity : Activity() {

    private val PREFS_NAME = "BlockerPrefs"
    private lateinit var etPin: EditText
    private lateinit var btnVerify: Button
    private lateinit var btnSave: Button

    private enum class Mode { VERIFY_UNLOCK, SET_NEW, VERIFY_OLD_TO_CHANGE }
    private var currentMode = Mode.SET_NEW
    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        etPin = findViewById(R.id.etPin)
        btnVerify = findViewById(R.id.btnVerifyPin)
        btnSave = findViewById(R.id.btnSavePin)

        val action = intent.getStringExtra("action")
        targetPackage = intent.getStringExtra("blocked_package")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPin = prefs.contains("pin_hash")

        if (targetPackage != null) {

            // --- SECURITY FIX: DISABLE SMART UNLOCK FOR SETTINGS ---
            val isSettings = targetPackage == "com.android.settings"

            if (!isSettings) {
                // Only try the "2 Free Unlocks" if it is NOT settings
                if (tryFreeUnlock(prefs)) return
            }
            // -----------------------------------------------------

            if (hasPin) {
                currentMode = Mode.VERIFY_UNLOCK
                setupUI(verify = true, "Verify to Unlock")
            } else {
                Toast.makeText(this, "No PIN set. Unlocking...", Toast.LENGTH_SHORT).show()
                unlockAndFinish()
            }
        } else {
            if (hasPin) {
                currentMode = Mode.VERIFY_OLD_TO_CHANGE
                setupUI(verify = true, "Verify Old PIN")
            } else {
                currentMode = Mode.SET_NEW
                setupUI(verify = false, "Set New PIN")
            }
        }
    }

    private fun tryFreeUnlock(prefs: SharedPreferences): Boolean {
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastDay = prefs.getInt("free_unlock_day", -1)
        val lastTime = prefs.getLong("free_unlock_time", 0L)
        var count = prefs.getInt("free_unlock_count", 0)

        if (currentDay != lastDay) {
            count = 0
            prefs.edit().putInt("free_unlock_day", currentDay).putInt("free_unlock_count", 0).apply()
        }

        if (count >= 2) return false

        val fortyFiveMins = 45 * 60 * 1000L
        if (System.currentTimeMillis() - lastTime < fortyFiveMins) return false

        prefs.edit()
            .putInt("free_unlock_count", count + 1)
            .putLong("free_unlock_time", System.currentTimeMillis())
            .apply()

        Toast.makeText(this, "Free Emergency Access (${count + 1}/2)", Toast.LENGTH_LONG).show()
        unlockAndFinish()
        return true
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

    private fun validatePin() {
        val input = etPin.text.toString()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedHash = prefs.getString("pin_hash", "")
        val storedSalt = prefs.getString("pin_salt", "")

        if (storedHash.isNullOrEmpty() || storedSalt.isNullOrEmpty()) {
            unlockAndFinish()
            return
        }

        if (hashPin(input, storedSalt) == storedHash) {
            when (currentMode) {
                Mode.VERIFY_OLD_TO_CHANGE -> {
                    currentMode = Mode.SET_NEW
                    setupUI(verify = false, "Save NEW PIN")
                    Toast.makeText(this, "Verified. Enter new PIN.", Toast.LENGTH_SHORT).show()
                }
                Mode.VERIFY_UNLOCK -> unlockAndFinish()
                else -> {}
            }
        } else {
            etPin.error = "Incorrect PIN"
            etPin.animate().translationX(20f).setDuration(100).withEndAction {
                etPin.animate().translationX(-20f).setDuration(100)
            }.start()
        }
    }

    private fun saveNewPin() {
        val input = etPin.text.toString()
        if (input.length != 6) {
            Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newSalt = UUID.randomUUID().toString()
        val newHash = hashPin(input, newSalt)

        prefs.edit().putString("pin_hash", newHash).putString("pin_salt", newSalt).apply()
        Toast.makeText(this, "PIN Secured", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun unlockAndFinish() {
        if (targetPackage != null) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // We must allow a timer, otherwise the Service will re-block Settings immediately
            // But since we disabled "tryFreeUnlock", the user MUST have entered the PIN to get here.
            val duration = 3 * 60 * 1000L
            val expiry = System.currentTimeMillis() + duration
            prefs.edit().putLong("temp_unlock_$targetPackage", expiry).apply()

            val intentTimer = Intent("com.arkadeep.shieldClan.START_TIMER")
            intentTimer.putExtra("duration", duration)
            intentTimer.putExtra("package", targetPackage)
            sendBroadcast(intentTimer)

            sendBroadcast(Intent("com.arkadeep.shieldClan.CLOSE_BLOCK_SCREEN"))
        }
        finish()
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest((pin + salt).toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}