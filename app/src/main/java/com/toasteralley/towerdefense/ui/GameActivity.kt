package com.toasteralley.towerdefense.ui

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.toasteralley.towerdefense.R
import com.toasteralley.towerdefense.SoundManager
import com.toasteralley.towerdefense.ads.AdManager
import com.toasteralley.towerdefense.app
import com.toasteralley.towerdefense.game.EnemyType
import com.toasteralley.towerdefense.game.GameEngine
import com.toasteralley.towerdefense.game.GameView
import com.toasteralley.towerdefense.game.RunModifiers
import com.toasteralley.towerdefense.game.RunResult
import kotlinx.coroutines.launch

class GameActivity : AppCompatActivity(), GameView.Listener {

    private lateinit var gameView: GameView
    private lateinit var adContainer: FrameLayout
    private lateinit var sound: SoundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        enterImmersiveMode()

        sound = SoundManager(app.settings)
        gameView = findViewById(R.id.game_view)
        adContainer = findViewById(R.id.ad_container)
        gameView.listener = this
        gameView.engineEvents = object : GameEngine.Events {
            override fun onTowerPlaced() = sound.play(SoundManager.Sfx.PLACE)
            override fun onTowerUpgraded() = sound.play(SoundManager.Sfx.UPGRADE)
            override fun onTowerSold() = sound.play(SoundManager.Sfx.SELL)
            override fun onLifeLost() = sound.play(SoundManager.Sfx.LIFE_LOST)
            override fun onWaveStarted(wave: Int) = sound.play(SoundManager.Sfx.WAVE)
            override fun onMilestone(wave: Int) = sound.play(SoundManager.Sfx.UPGRADE)
            override fun onEnemyKilled(type: EnemyType) = Unit
            // Called once per run (win, lose, restart or quit): persist Tokens,
            // lifetime gold (Super Token accrual), stats and best wave.
            override fun onRunFinished(result: RunResult) = app.progression.recordRun(result)
        }
        // Meta upgrades + prestige multiplier apply to this run.
        gameView.startRun(app.progression.runModifiers())

        val billing = app.billing
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    billing.entitlements.collect { ent ->
                        gameView.setPremiumUnlocked(ent.premiumTowers)
                        AdManager.bindBanner(this@GameActivity, adContainer, ent.removeAds)
                    }
                }
                launch { billing.messages.collect { Toast.makeText(this@GameActivity, it, Toast.LENGTH_LONG).show() } }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back toggles the pause menu instead of leaving mid-game.
                gameView.pauseGame()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        gameView.onHostResume()
        AdManager.onResume(adContainer)
    }

    override fun onPause() {
        gameView.onHostPause()
        AdManager.onPause(adContainer)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) gameView.finishRun()
        AdManager.releaseBanner(adContainer)
        sound.release()
        super.onDestroy()
    }

    // GameView.Listener
    override fun onOpenStore() {
        StoreDialog.show(this) { enterImmersiveMode() }
    }

    override fun onQuitToMenu() {
        gameView.finishRun()
        finish()
    }

    override fun currentModifiers(): RunModifiers = app.progression.runModifiers()
}
