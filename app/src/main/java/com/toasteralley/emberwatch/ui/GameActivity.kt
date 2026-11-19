package com.toasteralley.emberwatch.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.toasteralley.emberwatch.BuildConfig
import com.toasteralley.emberwatch.EmberwatchApp
import com.toasteralley.emberwatch.ads.AdManager
import com.toasteralley.emberwatch.core.DebugGate
import com.toasteralley.emberwatch.core.GameEngine
import com.toasteralley.emberwatch.core.ResearchProject
import com.toasteralley.emberwatch.core.RunRewards
import com.toasteralley.emberwatch.core.TowerStats
import com.toasteralley.emberwatch.core.UltimateWeapon
import com.toasteralley.emberwatch.debug.DebugTools
import com.toasteralley.emberwatch.debug.InGameDebug
import com.toasteralley.emberwatch.game.GameView

/** One run. Coins are banked into the save every few seconds and on exit. */
class GameActivity : AppCompatActivity(), GameView.Listener {
    private val app get() = EmberwatchApp.instance
    private val save get() = app.save
    private lateinit var engine: GameEngine
    private lateinit var view: GameView
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private val banker = object : Runnable {
        override fun run() { bank(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        engine = GameEngine(
            stats = TowerStats.resolve(save),
            ultimates = UltimateWeapon.entries.associateWith { save.level(it) },
            coinMultiplier = app.coinMultiplier(),
            coreMultiplier = ResearchProject.CORE_YIELD.effect(save.level(ResearchProject.CORE_YIELD)),
            tier = save.selectedTier,
            startWave = DebugGate.startWave(BuildConfig.DEBUG, save),
            godMode = DebugGate.godMode(BuildConfig.DEBUG, save),
        )
        view = GameView(this, engine).also { it.listener = this }
        setContentView(view)
        AdManager.preloadInterstitial(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { synchronized(engine) { if (engine.gameOver) onReturnToMenu() else engine.paused = !engine.paused } }
        })
    }

    override fun onResume() { super.onResume(); view.resume(); handler.postDelayed(banker, 5000) }
    override fun onPause() { super.onPause(); view.pause(); handler.removeCallbacks(banker); bank() }

    private fun bank() {
        synchronized(engine) { save.coins += engine.takeUnbankedCoins() }
        app.persist()
    }

    private fun endRun() {
        if (finished) return
        finished = true
        synchronized(engine) {
            save.coins += engine.takeUnbankedCoins()
            save.diamonds += engine.runDiamonds
            save.cores += engine.runCores
            RunRewards.applyRunEnd(save, engine.wave, engine.tier)
        }
        save.runsSinceInterstitial++
        app.persist()
    }

    override fun onRunEnded() { endRun() }

    override fun onReturnToMenu() {
        endRun()
        if (save.runsSinceInterstitial >= 3 && !app.noAds) {
            save.runsSinceInterstitial = 0; app.persist()
            AdManager.showInterstitial(this, app.noAds) { finish() }
        } else finish()
    }

    override fun onQuit() {
        synchronized(engine) { engine.paused = false }
        endRun(); finish()
    }

    override fun debugAvailable() = BuildConfig.DEBUG && DebugTools.AVAILABLE

    override fun onDebugGesture() {
        if (!DebugGate.canOpen(debugAvailable(), true)) return
        DebugTools.openInGame(this, object : InGameDebug {
            override fun skipWaves(n: Int) = synchronized(engine) { engine.debugSkipWaves(n) }
            override fun jumpToWave(n: Int) = synchronized(engine) { engine.debugJumpToWave(n) }
            override fun spawnBoss() { synchronized(engine) { engine.debugSpawnBoss() } }
            override fun godMode() = engine.godMode
            override fun setGodMode(on: Boolean) { engine.godMode = on; save.debugGodMode = on; app.persist() }
            override fun addCash(amount: Double) = synchronized(engine) { engine.debugAddCash(amount) }
            override fun heal() = synchronized(engine) { engine.debugHeal() }
            override fun resume() = synchronized(engine) { engine.paused = false }
        })
    }
}
