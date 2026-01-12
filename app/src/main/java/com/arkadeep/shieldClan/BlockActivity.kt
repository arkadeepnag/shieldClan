package com.arkadeep.shieldClan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class BlockActivity : Activity() {

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Received signal that PIN was correct or Grace Period granted
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)

        // Register receiver to close this screen remotely
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        registerReceiver(closeReceiver, IntentFilter("com.arkadeep.shieldClan.CLOSE_BLOCK_SCREEN"), flags)

        val tvShield: TextView = findViewById(R.id.tvShield)
        val tvSub: TextView = findViewById(R.id.tvSub)
        val btnGoHome: Button = findViewById(R.id.btnGoHome)
        val btnUnlock: Button = findViewById(R.id.btnUnlock)
        // val imgIcon: ImageView = findViewById(R.id.imgIcon) // If you added this

        val blockedPackage = intent.getStringExtra("blocked_package") ?: ""
        val blockedName = intent.getStringExtra("blocked_name") ?: blockedPackage

        tvShield.text = "SHIELD ACTIVE"
        tvSub.text = "$blockedName is blocked."

        btnGoHome.setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(home)
        }

        btnUnlock.setOnClickListener {
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("blocked_package", blockedPackage)
            startActivity(i)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(closeReceiver)
        } catch (e: Exception) {}
    }

    override fun onBackPressed() {
        // Prevent back button
    }
}