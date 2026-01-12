package com.arkadeep.shieldClan

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.security.MessageDigest
import java.util.UUID

object CloudManager {

    private const val PREFS_NAME = "BlockerPrefs"
    private const val TAG = "CloudManager"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun initListener(context: Context) {
        val deviceId = getDeviceId(context)
        val dbRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId)

        val initialData = mapOf(
            "online" to true,
            "last_active" to System.currentTimeMillis(),
            "device_model" to android.os.Build.MODEL
        )
        dbRef.updateChildren(initialData)

        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                var changesMade = false

                // 1. PIN CHANGE LOGIC
                if (snapshot.hasChild("new_pin")) {
                    val remotePin = snapshot.child("new_pin").value.toString()
                    val salt = prefs.getString("pin_salt", UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
                    val hashed = hashPin(remotePin, salt)

                    editor.putString("pin_salt", salt)
                    editor.putString("pin_hash", hashed)
                    editor.remove("pin_lockout_until")

                    // Remove the command from cloud so it doesn't loop
                    snapshot.child("new_pin").ref.removeValue()
                    changesMade = true
                }

                // 2. REMOTE BLOCK LIST UPDATE (NEW FEATURE)
                if (snapshot.hasChild("remote_blocks")) {
                    // Expecting a JSON String: [{"package":"com.facebook.katana", "name":"Facebook"}]
                    val blockJson = snapshot.child("remote_blocks").value.toString()

                    editor.putString("blocks_json", blockJson)
                    changesMade = true

                    // Notify the Service to update immediately
                    val intent = Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS")
                    context.sendBroadcast(intent)

                    // Remove command to allow local changes later
                    snapshot.child("remote_blocks").ref.removeValue()
                }

                // 3. EMERGENCY UNLOCK LOGIC
                val rawStatus = snapshot.child("status").value
                val lockStatus = rawStatus?.toString()

                if (lockStatus == "UNLOCK_ALL") {
                    editor.putString("blocks_json", "[]")
                    snapshot.child("status").ref.setValue("IDLE")
                    changesMade = true

                    val intent = Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS")
                    context.sendBroadcast(intent)
                }

                if (changesMade) editor.apply()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Db error: ${error.message}")
            }
        })
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest((pin + salt).toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}