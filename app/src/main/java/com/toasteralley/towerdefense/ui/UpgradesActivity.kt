package com.toasteralley.towerdefense.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.toasteralley.towerdefense.app
import com.toasteralley.towerdefense.meta.MetaUpgrade
import com.toasteralley.towerdefense.meta.Progression

/**
 * Out-of-run "Upgrades" screen (from the main menu): spend Tokens on permanent
 * global upgrades, and spend Super Tokens to prestige.
 */
class UpgradesActivity : AppCompatActivity() {

    private val progression: Progression get() = app.progression
    private lateinit var tokensText: TextView
    private lateinit var superText: TextView
    private lateinit var superProgress: ProgressBar
    private lateinit var superProgressText: TextView
    private lateinit var prestigeText: TextView
    private lateinit var prestigeBtn: Button
    private lateinit var statsText: TextView

    private class Row(val upgrade: MetaUpgrade, val level: TextView, val buy: Button)
    private val rows = mutableListOf<Row>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(16))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Upgrades"; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFD54F.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        root.addView(header)

        tokensText = TextView(this).apply { textSize = 20f; setTextColor(0xFF80DEEA.toInt()) }
        root.addView(tokensText)
        root.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.GRAY)
            text = "Tokens are earned at the end of every run (win or lose): " +
                "${Progression.TOKENS_PER_WAVE} per wave cleared, 1 per ${Progression.KILLS_PER_TOKEN} kills, " +
                "${Progression.TOKENS_PER_BOSS} per boss, +${Progression.VICTORY_TOKENS} for victory. " +
                "Upgrades apply to all towers in future runs."
        })

        // ---- Super Tokens / prestige card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF263238.toInt())
        }
        superText = TextView(this).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFAB40.toInt()) }
        card.addView(superText)
        superProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        card.addView(superProgress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        superProgressText = TextView(this).apply { textSize = 13f }
        card.addView(superProgressText)
        prestigeText = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE) }
        card.addView(prestigeText)
        prestigeBtn = Button(this).apply { text = "Reset (Prestige)"; setOnClickListener { confirmPrestige() } }
        card.addView(prestigeBtn)
        root.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        // ---- meta upgrades
        root.addView(TextView(this).apply {
            text = "Permanent upgrades"; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        })
        for (u in MetaUpgrade.entries) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply { text = u.displayName; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
            info.addView(TextView(this).apply { text = u.description; textSize = 12f; setTextColor(Color.GRAY) })
            val level = TextView(this).apply { textSize = 14f; setPadding(dp(8), 0, dp(8), 0) }
            info.addView(level)
            line.addView(info)
            val buy = Button(this).apply {
                setOnClickListener {
                    if (progression.buy(u)) refresh() else Toast.makeText(this@UpgradesActivity, "Not enough Tokens", Toast.LENGTH_SHORT).show()
                }
            }
            line.addView(buy)
            root.addView(line)
            rows += Row(u, level, buy)
        }

        statsText = TextView(this).apply { textSize = 12f; setTextColor(0xFFB0BEC5.toInt()); setPadding(0, dp(12), 0, 0) }
        root.addView(statsText)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF102027.toInt())
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        refresh()
    }

    private fun refresh() {
        val p = progression
        tokensText.text = "Tokens: ${p.tokens}"
        superText.text = "Super Tokens: ${p.superTokensAvailable} available  (${p.superTokensEarned} earned, ${p.superTokensSpent} spent)"
        superProgress.progress = (p.superTokenProgress * 1000).toInt()
        superProgressText.text = "Lifetime gold earned (all runs): ${p.lifetimeGold}  ·  next Super Token at ${p.goldForNextSuperToken} gold. " +
            "Super Tokens are never sold; they accrue from gold earned in play (each needs more gold)."
        prestigeText.text = "Prestige damage multiplier: ×%.2f for every tower  ·  prestiges: %d".format(p.prestigeMultiplier, p.prestigeCount)
        prestigeBtn.isEnabled = p.superTokensAvailable > 0
        for (r in rows) {
            val lvl = p.level(r.upgrade)
            val max = r.upgrade.maxLevel
            r.level.text = if (lvl >= max) "Level $lvl/$max  ${r.upgrade.format(lvl)} (MAX)"
            else "Level $lvl/$max  ${r.upgrade.format(lvl)} → ${r.upgrade.format(lvl + 1)}"
            val cost = p.nextCost(r.upgrade)
            r.buy.text = if (cost < 0) "Maxed" else "Buy $cost T"
            r.buy.isEnabled = cost in 0..p.tokens
        }
        statsText.text = "Best wave ${p.bestWave} · runs ${p.runsPlayed} · kills ${p.totalKills} · bosses ${p.totalBossKills} · " +
            "Tokens earned ${p.totalTokensEarned} · gold this prestige ${p.cycleGold}"
    }

    private fun confirmPrestige() {
        val p = progression
        val avail = p.superTokensAvailable
        if (avail <= 0) return
        var amount = avail
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply {
            textSize = 14f
            text = "Spending Super Tokens resets your progression to the baseline:\n" +
                "  • WIPED: all Tokens (${p.tokens}), every permanent upgrade level, best wave and run stats.\n" +
                "  • KEPT: purchases (Remove Ads, Premium Towers), settings, the prestige multiplier, " +
                "total Super Tokens spent, and any Super Tokens you don't spend now.\n" +
                "Each Super Token can only be earned once; lifetime gold keeps counting toward the next one.\n\n" +
                "In exchange every tower's base damage multiplier rises permanently " +
                "(×(1 + 0.1 × totalSpent^0.8))."
        })
        val preview = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) }
        fun updatePreview() {
            val before = Progression.prestigeMultiplierFor(p.superTokensSpent)
            val after = Progression.prestigeMultiplierFor(p.superTokensSpent + amount)
            preview.text = "Spend $amount of $avail Super Tokens: ×%.2f → ×%.2f damage".format(before, after)
        }
        box.addView(preview)
        if (avail > 1) {
            box.addView(SeekBar(this).apply {
                max = avail - 1
                progress = avail - 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { amount = v + 1; updatePreview() }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        updatePreview()
        AlertDialog.Builder(this)
            .setTitle("Reset (Prestige)?")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Reset & spend") { _, _ ->
                if (progression.prestige(amount)) {
                    Toast.makeText(this, "Prestige complete: ×%.2f damage".format(progression.prestigeMultiplier), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { enterImmersiveMode() }
            .show()
    }
}
