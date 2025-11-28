package com.arkadeep.shieldClan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class BlockActivity : Activity() {

    private lateinit var tvShield: TextView
    private lateinit var tvSub: TextView
    private lateinit var btnGoHome: Button
    private lateinit var btnUnlock: Button

    private var blockedPackage: String = ""
    private var blockedName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)

        tvShield = findViewById(R.id.tvShield)
        tvSub = findViewById(R.id.tvSub)
        btnGoHome = findViewById(R.id.btnGoHome)
        btnUnlock = findViewById(R.id.btnUnlock)

        blockedPackage = intent.getStringExtra("blocked_package") ?: ""
        blockedName = intent.getStringExtra("blocked_name") ?: blockedPackage

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
            i.putExtra("action", "verify_and_remove")
            i.putExtra("blocked_package", blockedPackage)
            startActivity(i)
        }
    }

    override fun onBackPressed() {  }
}
