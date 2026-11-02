package com.toasteralley.towerdefense.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.toasteralley.towerdefense.R
import com.toasteralley.towerdefense.ads.AdManager
import com.toasteralley.towerdefense.app
import kotlinx.coroutines.launch

class MainMenuActivity : AppCompatActivity() {

    private lateinit var adContainer: FrameLayout
    private lateinit var bestWave: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)
        enterImmersiveMode()

        adContainer = findViewById(R.id.ad_container)
        bestWave = findViewById(R.id.best_wave)

        findViewById<Button>(R.id.btn_play).setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
        findViewById<Button>(R.id.btn_upgrades).setOnClickListener {
            startActivity(Intent(this, UpgradesActivity::class.java))
        }
        findViewById<Button>(R.id.btn_store).setOnClickListener { StoreDialog.show(this) }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val billing = app.billing
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { billing.entitlements.collect { AdManager.bindBanner(this@MainMenuActivity, adContainer, it.removeAds) } }
                launch { billing.messages.collect { Toast.makeText(this@MainMenuActivity, it, Toast.LENGTH_LONG).show() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        AdManager.onResume(adContainer)
        // Pick up purchases made outside the app (e.g. pending purchases that completed).
        app.billing.restorePurchasesAsync()
        val p = app.progression
        val best = p.bestWave
        val line = StringBuilder()
        if (best > 0) line.append(getString(R.string.best_wave, best)).append("  ·  ")
        line.append(getString(R.string.tokens_line, p.tokens, p.superTokensAvailable, p.prestigeMultiplier))
        bestWave.text = line
    }

    override fun onPause() {
        AdManager.onPause(adContainer)
        super.onPause()
    }

    override fun onDestroy() {
        AdManager.releaseBanner(adContainer)
        super.onDestroy()
    }
}
