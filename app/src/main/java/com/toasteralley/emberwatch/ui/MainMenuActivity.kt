package com.toasteralley.emberwatch.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toasteralley.emberwatch.BuildConfig
import com.toasteralley.emberwatch.EmberwatchApp
import com.toasteralley.emberwatch.R
import com.toasteralley.emberwatch.ads.AdManager
import com.toasteralley.emberwatch.core.CoinMultipliers
import com.toasteralley.emberwatch.core.DebugGate
import com.toasteralley.emberwatch.core.Gates
import com.toasteralley.emberwatch.core.NumberFormat
import com.toasteralley.emberwatch.core.Palette
import com.toasteralley.emberwatch.core.SecretGesture
import com.toasteralley.emberwatch.core.StageDefinitions
import com.toasteralley.emberwatch.core.Stages
import com.toasteralley.emberwatch.debug.DebugTools
import kotlinx.coroutines.launch

/**
 * Hub of the infinite loop: run -> fall -> spend -> run again.
 * Hidden management override (debug builds only): hold the gear icon for 2 s,
 * then tap the EMBERWATCH title 5 times within 3 s.
 */
class MainMenuActivity : AppCompatActivity() {
    private val app get() = EmberwatchApp.instance
    private val save get() = app.save
    private val gesture = SecretGesture()
    private lateinit var content: LinearLayout
    private lateinit var banner: FrameLayout
    private var gearDownAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.menu_background)
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val top = FrameLayout(this)
        val gear = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings); background = null
            contentDescription = getString(R.string.settings)
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> gearDownAt = System.currentTimeMillis()
                    MotionEvent.ACTION_UP -> {
                        val held = System.currentTimeMillis() - gearDownAt
                        if (held >= SecretGesture.LONG_PRESS_MS && DebugTools.AVAILABLE) gesture.onSettingsHeld(held)
                        else v.performClick()
                    }
                }
                true
            }
            setOnClickListener { startActivity(Intent(this@MainMenuActivity, SettingsActivity::class.java)) }
        }
        top.addView(gear, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END))
        root.addView(top)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        banner = FrameLayout(this)
        root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        edgeToEdge(root)
        lifecycleScope.launch { app.billing.owned.collect { AdManager.bindBanner(this@MainMenuActivity, banner, app.noAds); build() } }
        AdManager.preloadRewarded(this)
    }

    override fun onResume() {
        super.onResume()
        app.tickResearch()
        app.billing.restorePurchasesAsync()
        build()
    }

    private fun build() {
        content.removeAllViews()
        val title = label(getString(R.string.menu_title), 40f, Palette.CORAL, bold = true).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.12f; setPadding(0, dp(24), 0, 0)
            setOnClickListener {
                if (DebugGate.canOpen(BuildConfig.DEBUG && DebugTools.AVAILABLE, gesture.onTitleTap())) DebugTools.openMenu(this@MainMenuActivity)
            }
        }
        content.addView(title)
        content.addView(label(getString(R.string.menu_subtitle), 15f, Palette.STEEL_LIGHT).apply { gravity = Gravity.CENTER })
        val now = System.currentTimeMillis()
        val boostLeft = (save.boostUntilMs - now).coerceAtLeast(0)
        content.addView(label("Best wave ${save.bestWave} · Coins ${NumberFormat.compact(save.coins)} · ◆ ${save.diamonds} · Cores ${save.cores}\n" +
            "Coin multiplier x${NumberFormat.compact(app.coinMultiplier(now))}" + if (boostLeft > 0) " (boost ${NumberFormat.duration(boostLeft)})" else "",
            13f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)) })

        // stage selector (Stages 1-24, see core/StageDefinitions.kt)
        val maxStage = Stages.highestUnlocked(save, app.bypassGates)
        save.selectedStage = save.selectedStage.coerceIn(1, maxStage)
        val st = save.selectedStage
        val def = StageDefinitions.get(st)
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(emberButton("◀", st > 1) { save.selectedStage--; app.persist(); build() })
            addView(label("  Stage $st / ${StageDefinitions.STAGE_COUNT}  ", 18f, Palette.TEXT, bold = true))
            addView(emberButton("▶", st < maxStage) { save.selectedStage++; app.persist(); build() })
        })
        val next = Stages.nextMilestone(save, st)
        val info = StringBuilder("Best cleared: ${Stages.best(save, st)} · HP x${NumberFormat.compact(def.hpMult)} · coins x${NumberFormat.compact(def.coinMult)}\n")
        info.append(if (next == null) "All Stage $st rewards earned" else
            "Next reward at wave ${next.wave}: ◆${next.reward.diamonds}, ${next.reward.cores} cores" +
                (if (next.reward.coinBonus > 0) ", +${(next.reward.coinBonus * 100).toInt()}% coins" else "") +
                " · full rewards at ${def.fullRewardWave}")
        if (st < StageDefinitions.STAGE_COUNT && !Stages.isUnlocked(save, st + 1, app.bypassGates))
            info.append("\nStage ${st + 1} unlocks after clearing wave ${StageDefinitions.get(st + 1).unlockWave} here")
        content.addView(label(info, 12f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER })

        fun menu(text: String, gate: Int, cls: Class<*>) {
            val open = Gates.open(gate, save, app.bypassGates)
            content.addView(emberButton(if (open) text else "$text  (wave $gate)", open) {
                startActivity(Intent(this, cls))
            }, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        content.addView(emberButton(getString(R.string.battle)) { startActivity(Intent(this, GameActivity::class.java)) }.apply {
            textSize = 20f; background = roundedBg(Palette.CORAL_DIM, Palette.CORAL, dp(12).toFloat())
        }, LinearLayout.LayoutParams(dp(280), dp(60)).apply { topMargin = dp(16) })
        menu("Workshop", 0, WorkshopActivity::class.java)
        menu("Research Bay", 0, ResearchActivity::class.java)
        menu("Ultimate Weapons", Gates.ULTIMATES_WAVE, UltimatesActivity::class.java)
        menu("Diamond Perks", Gates.PERKS_WAVE, PerksActivity::class.java)
        menu("Enhancement", Gates.ENHANCEMENT_WAVE, EnhancementActivity::class.java)
        menu("Store", 0, StoreActivity::class.java)
        content.addView(emberButton("Watch ad: +20 min 1.5x coins", CoinMultipliers.canAddChunk(save, now)) {
            AdManager.showRewarded(this, onReward = {
                CoinMultipliers.addRewardedChunk(save, System.currentTimeMillis()); app.persist(); build()
            }, onUnavailable = { android.widget.Toast.makeText(this, "Ad not ready yet", android.widget.Toast.LENGTH_SHORT).show() })
        }, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
    }
}
