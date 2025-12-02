package com.arkadeep.shieldClan

import android.content.Context
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
                if (!snapshot.exists()) return

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                var changesMade = false

                val rawPin = snapshot.child("new_pin").value
                val remotePin = rawPin?.toString()

                if (!remotePin.isNullOrEmpty()) {
                    Log.d(TAG, "Received new PIN from cloud: $remotePin")

                    val salt = prefs.getString("pin_salt", UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
                    val hashed = hashPin(remotePin, salt)

                    editor.putString("pin_salt", salt)
                    editor.putString("pin_hash", hashed)
                    editor.putInt("pin_attempts", 0)
                    editor.remove("pin_lockout_until")


                    snapshot.child("new_pin").ref.removeValue()
                    changesMade = true
                }

                val rawStatus = snapshot.child("status").value
                val lockStatus = rawStatus?.toString()

                if (lockStatus == "UNLOCK_ALL") {
                    editor.putString("blocks_json", "[]")
                    snapshot.child("status").ref.setValue("IDLE")
                    changesMade = true

                    val intent = android.content.Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS")
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