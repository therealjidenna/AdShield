package com.adshield.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var stats: TextView
    private lateinit var recent: TextView
    private lateinit var customBox: LinearLayout
    private lateinit var input: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggleButton)
        stats = findViewById(R.id.stats)
        recent = findViewById(R.id.recent)
        customBox = findViewById(R.id.customBox)
        input = findViewById(R.id.domainInput)

        toggle.setOnClickListener {
            if (AdVpnService.running) stopBlocking() else startBlocking()
        }
        findViewById<Button>(R.id.addButton).setOnClickListener { addDomain() }

        Blocklist.load(this)
        renderCustom()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun startBlocking() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQ_VPN)
        } else {
            launchService()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            launchService()
        }
    }

    private fun launchService() {
        startService(Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_START))
        handler.postDelayed({ refresh() }, 400)
    }

    private fun stopBlocking() {
        startService(Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_STOP))
        handler.postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        val on = AdVpnService.running
        status.text = if (on) "Protection is ON" else "Protection is OFF"
        status.setTextColor(if (on) 0xFF4CAF50.toInt() else 0xFFE57373.toInt())
        toggle.text = if (on) "Turn off" else "Turn on"
        stats.text = "${AdStats.blocked.get()} blocked of ${AdStats.total.get()} lookups"
        val r = AdStats.recentList()
        recent.text = if (r.isEmpty()) "Nothing blocked yet" else r.joinToString("\n")
    }

    private fun addDomain() {
        val d = Blocklist.normalize(input.text.toString())
        if (d == null) {
            Toast.makeText(this, "That doesn't look like a domain", Toast.LENGTH_SHORT).show()
            return
        }
        Blocklist.addCustom(this, d)
        input.setText("")
        renderCustom()
        Toast.makeText(this, "Blocked $d", Toast.LENGTH_SHORT).show()
    }

    private fun renderCustom() {
        customBox.removeAllViews()
        val list = Blocklist.customDomains(this).sorted()
        if (list.isEmpty()) {
            customBox.addView(TextView(this).apply {
                text = "None yet"
                alpha = 0.6f
                setPadding(0, 8, 0, 8)
            })
            return
        }
        for (d in list) {
            customBox.addView(TextView(this).apply {
                text = "✕   $d"
                textSize = 16f
                setPadding(0, 16, 0, 16)
                setOnClickListener(View.OnClickListener {
                    Blocklist.removeCustom(this@MainActivity, d)
                    renderCustom()
                })
            })
        }
    }

    companion object {
        private const val REQ_VPN = 1
    }
}
