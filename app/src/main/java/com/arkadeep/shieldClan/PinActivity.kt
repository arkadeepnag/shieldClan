package com.arkadeep.shieldClan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

class PinActivity : Activity() {

    private val PREFS_NAME = "BlockerPrefs"
    private lateinit var etPin: EditText
    private lateinit var btnVerify: Button
    private lateinit var btnSave: Button
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView

    // State definitions
    private enum class Mode { VERIFY_UNLOCK, SET_NEW, VERIFY_OLD_TO_CHANGE }
    private var currentMode = Mode.SET_NEW
    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        etPin = findViewById(R.id.etPin)
        btnVerify = findViewById(R.id.btnVerifyPin)
        btnSave = findViewById(R.id.btnSavePin)

        // Safety fallback if IDs are missing in XML
        tvTitle = findViewById(R.id.tvShield) ?: TextView(this)
        tvSub = findViewById(R.id.tvSub) ?: TextView(this)

        // "action" can be "set_pin" or "verify_and_remove"
        val action = intent.getStringExtra("action")
        targetPackage = intent.getStringExtra("blocked_package")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPin = prefs.contains("pin_hash")

        // --- FIXED LOGIC START ---

        // CASE 1: User is trying to UNLOCK a blocked app
        if (targetPackage != null) {
            if (hasPin) {
                currentMode = Mode.VERIFY_UNLOCK
                setupUI(verify = true, "Verify to Unlock")
            } else {
                // If no PIN is set but app is blocked, safe fallback to unlock
                Toast.makeText(this, "No PIN set. Unlocking...", Toast.LENGTH_SHORT).show()
                unlockAndFinish()
            }
        }
        // CASE 2: User is in the Main Menu clicking "Manage PIN"
        else {
            if (hasPin) {
                // If they have a PIN, they must verify it before changing it
                currentMode = Mode.VERIFY_OLD_TO_CHANGE
                setupUI(verify = true, "Verify Old PIN")
            } else {
                // If no PIN exists, let them set a new one immediately
                currentMode = Mode.SET_NEW
                setupUI(verify = false, "Set New PIN")
            }
        }
        // --- FIXED LOGIC END ---
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
            // Fallback if data corrupted
            unlockAndFinish()
            return
        }

        if (hashPin(input, storedSalt) == storedHash) {
            // SUCCESS: PIN MATCHED
            when (currentMode) {
                Mode.VERIFY_OLD_TO_CHANGE -> {
                    // Transition to Set New Mode
                    currentMode = Mode.SET_NEW
                    setupUI(verify = false, "Save NEW PIN")
                    Toast.makeText(this, "Old PIN Verified. Enter new one.", Toast.LENGTH_LONG).show()
                }
                Mode.VERIFY_UNLOCK -> {
                    unlockAndFinish()
                }
                else -> {}
            }
        } else {
            // ERROR: WRONG PIN
            etPin.error = "Incorrect PIN"
            // Simple shake animation
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

        prefs.edit()
            .putString("pin_hash", newHash)
            .putString("pin_salt", newSalt)
            .apply()

        Toast.makeText(this, "PIN Secured", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun unlockAndFinish() {
        if (targetPackage != null) {
            removeBlock(targetPackage!!)

            // Go Home to close the blocked app overlay
            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(home)
        }
        finish()
    }

    private fun removeBlock(pkg: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("blocks_json", "[]")
        val arr = JSONArray(jsonStr)
        val newArr = JSONArray()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("package") != pkg) {
                newArr.put(o)
            }
        }

        prefs.edit().putString("blocks_json", newArr.toString()).apply()

        // IMPORTANT: Notify Service to update cache immediately
        sendBroadcast(Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS"))
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest((pin + salt).toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}