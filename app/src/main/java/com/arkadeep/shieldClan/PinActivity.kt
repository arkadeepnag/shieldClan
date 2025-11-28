package com.arkadeep.shieldClan

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        val etPin = findViewById<EditText>(R.id.etPin)
        val btnSave = findViewById<Button>(R.id.btnSavePin)
        val btnVerify = findViewById<Button>(R.id.btnVerifyPin)

        val action = intent.getStringExtra("action")

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val existingHash = prefs.getString(PIN_HASH, null)
        val existingSalt = prefs.getString(PIN_SALT, null)

        if (action == "set_pin") {

            btnSave.visibility = Button.VISIBLE
            btnVerify.visibility = Button.GONE

            btnSave.setOnClickListener {
                val pin = etPin.text.toString().trim()
                if (pin.length != 6 || !pin.all { it.isDigit() }) {
                    Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val salt = existingSalt ?: UUID.randomUUID().toString()
                val hashed = hashPin(pin, salt)
                prefs.edit().putString(PIN_SALT, salt).putString(PIN_HASH, hashed)
                    .putInt(ATTEMPTS_KEY, 0).remove(LOCKOUT_KEY).apply()
                Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show()
                finish()
            }

        } else {
            // verify-only flow (no PIN creation)
            btnSave.visibility = Button.GONE
            btnVerify.visibility = Button.VISIBLE

            btnVerify.setOnClickListener {
                val now = System.currentTimeMillis()
                val lockoutUntil = prefs.getLong(LOCKOUT_KEY, 0L)
                if (now < lockoutUntil) {
                    val remainS = (lockoutUntil - now) / 1000
                    Toast.makeText(this, "Locked. Try again in ${remainS}s", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val entered = etPin.text.toString().trim()
                if (existingHash == null || existingSalt == null) {
                    Toast.makeText(this, "No PIN set. Go to Set PIN first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val hashed = hashPin(entered, existingSalt)
                if (hashed == existingHash) {
                    // success -> reset attempts
                    prefs.edit().putInt(ATTEMPTS_KEY, 0).remove(LOCKOUT_KEY).apply()
                    Toast.makeText(this, "PIN correct", Toast.LENGTH_SHORT).show()

                    val actionType = intent.getStringExtra("action")
                    if (actionType == "verify_and_remove") {
                        val pkg = intent.getStringExtra("blocked_package") ?: ""
                        if (pkg.isNotEmpty()) {
                            // remove block
                            removeBlock(pkg)
                        }
                    }
                    finish()
                } else {
                    // increment attempts
                    var attempts = prefs.getInt(ATTEMPTS_KEY, 0)
                    attempts++
                    val edit = prefs.edit().putInt(ATTEMPTS_KEY, attempts)
                    if (attempts >= MAX_ATTEMPTS) {
                        val lockUntil = System.currentTimeMillis() + LOCKOUT_MS
                        edit.putLong(LOCKOUT_KEY, lockUntil)
                    }
                    edit.apply()
                    val left = MAX_ATTEMPTS - attempts
                    if (left <= 0) {
                        Toast.makeText(this, "Too many attempts. Locked for 5 minutes.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Wrong PIN. Attempts left: $left", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
