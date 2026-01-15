package com.arkadeep.shieldClan

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
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

    fun uploadPinConfig(context: Context, pinCode: String) {
        val deviceId = getDeviceId(context)
        val dbRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId)

        // Only upload if different to avoid loops
        dbRef.child("pin_code").setValue(pinCode)
        dbRef.child("last_updated").setValue(System.currentTimeMillis())
    }

    fun initListener(context: Context) {
        val deviceId = getDeviceId(context)
        val dbRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId)

        val initialData = mapOf(
            "online" to true,
            "device_model" to android.os.Build.MODEL
        )
        dbRef.updateChildren(initialData)

        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                var changesMade = false

                // --- FIX 1: LISTEN FOR DIRECT PIN CHANGES ---
                // We check 'pin_code' directly. If Cloud value != Local value, we update Local.
                val cloudPin = snapshot.child("pin_code").value?.toString()
                val localPin = prefs.getString("pin_code", "")

                if (!cloudPin.isNullOrEmpty() && cloudPin != localPin) {
                    Log.d(TAG, "Cloud PIN change detected: $cloudPin")
                    editor.putString("pin_code", cloudPin)

                    // Reset security counters on change
                    editor.putInt("pin_attempts", 0)
                    editor.remove("pin_lockout_until")

                    changesMade = true
                }
                // -------------------------------------------

                // Support for "new_pin" command (Legacy support)
                val commandPin = snapshot.child("new_pin").value?.toString()
                if (!commandPin.isNullOrEmpty()) {
                    editor.putString("pin_code", commandPin)
                    snapshot.child("new_pin").ref.removeValue() // Delete command
                    uploadPinConfig(context, commandPin) // Sync back to 'pin_code'
                    changesMade = true
                }

                if (snapshot.hasChild("remote_blocks")) {
                    val blockJson = snapshot.child("remote_blocks").value.toString()
                    editor.putString("blocks_json", blockJson)
                    snapshot.child("remote_blocks").ref.removeValue()

                    val intent = Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS")
                    context.sendBroadcast(intent)
                    changesMade = true
                }

                if (snapshot.child("status").value?.toString() == "UNLOCK_ALL") {
                    editor.putString("blocks_json", "[]")
                    snapshot.child("status").ref.setValue("IDLE")

                    val intent = Intent("com.arkadeep.shieldClan.UPDATE_BLOCKS")
                    context.sendBroadcast(intent)
                    changesMade = true
                }

                if (changesMade) editor.apply()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Db error: ${error.message}")
            }
        })
    }
}