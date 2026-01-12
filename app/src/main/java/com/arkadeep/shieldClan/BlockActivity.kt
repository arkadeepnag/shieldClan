package com.arkadeep.shieldClan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class BlockActivity : Activity() {

    // 1. The Scolding Quotes
    private val quotes = listOf(
        "THEY DON'T KNOW ME SON!",
        "WHO'S GONNA CARRY THE BOATS?",
        "STAY HARD!",
        "YOU WANT TO BE AVERAGE?",
        "DISCIPLINE EQUALS FREEDOM!",
        "WHY ARE YOU HERE?",
        "DON'T GET COMFORTABLE!",
        "SUFFERING IS GROWTH!"
    )

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)

        // Register receiver to close screen remotely
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        registerReceiver(closeReceiver, IntentFilter("com.arkadeep.shieldClan.CLOSE_BLOCK_SCREEN"), flags)

        val tvScold: TextView = findViewById(R.id.tvScold)
        val imgGoggins: ImageView = findViewById(R.id.imgGoggins)
        val btnGoHome: Button = findViewById(R.id.btnGoHome)
        val btnUnlock: View = findViewById(R.id.btnUnlock) // Treated as View since it might be TextView or Button

        val blockedPackage = intent.getStringExtra("blocked_package") ?: ""

        // 2. Set Random Quote
        tvScold.text = quotes.random()

        // 3. The "POP" Animation
        // Start pushed down off the screen (positive Y value)
        imgGoggins.translationY = 1000f

        // Animate up to 0 (original position)
        imgGoggins.animate()
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(200) // Wait a tiny bit after screen load
            .setInterpolator(OvershootInterpolator(1.2f)) // Makes it bounce slightly
            .withEndAction {
                // After he pops up, show the speech bubble
                tvScold.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            .start()

        // 4. Button Logic
        btnGoHome.setOnClickListener {
            // "Retreat" -> Go Home
            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(home)
        }

        btnUnlock.setOnClickListener {
            // "I'm Weak" -> Go to Pin Screen
            val i = Intent(this, PinActivity::class.java)
            i.putExtra("action", "verify_and_remove")
            i.putExtra("blocked_package", blockedPackage)
            startActivity(i)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(closeReceiver) } catch (e: Exception) {}
    }

    override fun onBackPressed() {
        // Disable back button to force a choice
    }
}