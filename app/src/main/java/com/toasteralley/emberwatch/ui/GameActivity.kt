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
import com.toasteralley.emberwatch.core.GameAchievement
import com.toasteralley.emberwatch.core.GameEvent
import com.toasteralley.emberwatch.core.GameEvents
import com.toasteralley.emberwatch.core.Ultimate
import com.toasteralley.emberwatch.debug.DebugTools
import com.toasteralley.emberwatch.debug.InGameDebug
import com.toasteralley.emberwatch.game.GameView

/** One run. Bounty are banked into the save every few seconds and on exit. */
class GameActivity : AppCompatActivity(), GameView.Listener {
    private val app get() = EmberwatchApp.instance
    private val save get() = app.save
    private lateinit var engine: GameEngine
    private lateinit var view: GameView
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var lastReward: com.toasteralley.emberwatch.core.StageReward? = null
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
            save = save,
            ultimates = com.toasteralley.emberwatch.core.UltimateLoadout.from(save),
            bountyMultiplier = app.bountyMultiplier(),
            coreMultiplier = ResearchProject.CORE_YIELD.effect(save.level(ResearchProject.CORE_YIELD)),
            stage = save.selectedStage,
            enhancementLevel = save.enhancement,
            startWave = DebugGate.startWave(BuildConfig.DEBUG, save),
            godMode = DebugGate.godMode(BuildConfig.DEBUG, save),
            bypassGates = app.bypassGates,
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
        synchronized(engine) { save.bounty += engine.takeUnbankedBounty() }
        drainStoneEvents()
        app.persist()
    }

    /** Applies Stone events raised by the engine (boss kills) and shows a small "Stones +N" toast. */
    private fun drainStoneEvents() {
        val events = synchronized(engine) { engine.stoneEvents.toList().also { engine.stoneEvents.clear() } }
        if (events.isEmpty()) return
        var n = GameEvents.handleAll(save, events)
        if (events.any { it is GameEvent.BossKill }) n += GameEvents.handle(save, GameEvent.Achievement(GameAchievement.FIRST_BOSS))
        if (n > 0) { app.persist(); android.widget.Toast.makeText(this, "Stones +$n", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun endRun() {
        if (finished) return
        finished = true
        synchronized(engine) {
            save.bounty += engine.takeUnbankedBounty()
            save.diamonds += engine.runDiamonds
            save.cores += engine.runCores
            lastReward = RunRewards.applyRunEnd(save, engine.wave, engine.stage)
        }
        drainStoneEvents()
        lastReward?.stones?.takeIf { it > 0 }?.let { android.widget.Toast.makeText(this, "Stones +$it", android.widget.Toast.LENGTH_SHORT).show() }
        save.runsSinceInterstitial++
        app.persist()
    }

    override fun onRunEnded() { endRun() }

    override fun onReturnToMenu() {
        endRun()
        lastReward?.takeIf { it.diamonds > 0 || it.cores > 0 }?.let {
            android.widget.Toast.makeText(this, "Stage ${engine.stage} milestones: +${it.diamonds} ◆, +${it.cores} cores" +
                (if (it.bountyBonus > 0) ", +${"%.1f".format(it.bountyBonus * 100)}% Bounty forever" else ""), android.widget.Toast.LENGTH_LONG).show()
        }
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
            override fun ultimateNames() = Ultimate.entries.map { it.label }
            override fun triggerUltimate(index: Int) = synchronized(engine) { engine.debugTrigger(Ultimate.entries[index], forceUnlock = true) }
        })
    }
}
