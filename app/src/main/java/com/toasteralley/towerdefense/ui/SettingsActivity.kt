package com.toasteralley.towerdefense.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.toasteralley.towerdefense.BuildConfig
import com.toasteralley.towerdefense.R
import com.toasteralley.towerdefense.app

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        enterImmersiveMode()

        val settings = app.settings
        findViewById<SwitchCompat>(R.id.switch_sound).apply {
            isChecked = settings.soundEnabled
            setOnCheckedChangeListener { _, checked -> settings.soundEnabled = checked }
        }
        findViewById<Button>(R.id.btn_reset_best).setOnClickListener { app.progression.resetBestWave() }
        findViewById<Button>(R.id.btn_restore).setOnClickListener {
            app.billing.restorePurchasesAsync(userInitiated = true)
        }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.version).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.billing.messages.collect { Toast.makeText(this@SettingsActivity, it, Toast.LENGTH_LONG).show() }
            }
        }
    }
}
