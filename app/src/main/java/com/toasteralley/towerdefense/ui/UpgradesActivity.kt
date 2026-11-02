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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.toasteralley.towerdefense.app
import com.toasteralley.towerdefense.game.GoldFormat
import com.toasteralley.towerdefense.meta.MetaUpgrade
import com.toasteralley.towerdefense.meta.PrestigeAllocation
import com.toasteralley.towerdefense.meta.PrestigeUpgrade
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
                "${Progression.TOKENS_PER_BOSS} per boss, +${Progression.VICTORY_TOKENS} for the wave-20 victory milestone. " +
                "Beyond wave 20 (endless), waves, kills and bosses pay logarithmically less per unit. " +
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
        superProgressText.text = "Lifetime gold earned (all runs): ${GoldFormat.compact(p.lifetimeGold)}  ·  next Super Token at ${GoldFormat.compact(p.goldForNextSuperToken)} gold. " +
            "Super Tokens are never sold; they accrue from gold earned in play (each needs more gold)."
        prestigeText.text = PrestigeUpgrade.entries.joinToString("\n") { u ->
            "%s: ×%.2f  (%d Super Tokens)".format(u.displayName, p.prestigeMultiplier(u), p.spent(u))
        } + "\nPrestiges: ${p.prestigeCount}"
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
            "Tokens earned ${p.totalTokensEarned} · gold this prestige ${GoldFormat.compact(p.cycleGold)}"
    }

    private fun confirmPrestige() {
        val p = progression
        val avail = p.superTokensAvailable
        if (avail <= 0) return
        // Default: everything into damage; the player redistributes with +/-.
        var alloc = PrestigeAllocation(damage = avail)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply {
            textSize = 13f
            text = "Spending Super Tokens resets your progression to the baseline:\n" +
                "  • WIPED: all Tokens (${p.tokens}), every permanent upgrade level, best wave and run stats.\n" +
                "  • KEPT: purchases (Remove Ads, Premium Towers), settings, all prestige multipliers, " +
                "Super Tokens spent so far, and any Super Tokens you don't allocate now.\n" +
                "Each Super Token can only be earned once; lifetime gold keeps counting toward the next one.\n\n" +
                "Split your $avail Super Tokens across three permanent upgrades:\n" +
                "  Damage ×(1 + 0.1·n^0.8) · Gold per Kill ×min(2.5, 1 + 0.05·n^0.7) · Gold per Wave ×min(2.0, 1 + 0.05·n^0.7)\n" +
                "(n = total Super Tokens ever spent in that category)."
        })
        val remainingText = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, dp(4)) }
        box.addView(remainingText)
        val rowTexts = mutableMapOf<PrestigeUpgrade, TextView>()
        lateinit var confirm: () -> Unit
        fun update() {
            val left = avail - alloc.total
            remainingText.text = "Allocated ${alloc.total} of $avail  ·  $left kept unspent"
            for (u in PrestigeUpgrade.entries) {
                val before = p.spent(u)
                val after = before + alloc[u]
                rowTexts[u]?.text = "%s: %d  →  ×%.2f → ×%.2f".format(
                    u.displayName, alloc[u], u.multiplier(before), u.multiplier(after)
                )
            }
            confirm()
        }
        for (u in PrestigeUpgrade.entries) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply {
                textSize = 14f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            rowTexts[u] = label
            row.addView(label)
            row.addView(Button(this).apply {
                text = "−"
                setOnClickListener { if (alloc[u] > 0) { alloc = alloc.with(u, alloc[u] - 1); update() } }
            }, LinearLayout.LayoutParams(dp(52), WRAP_CONTENT))
            row.addView(Button(this).apply {
                text = "+"
                setOnClickListener {
                    if (alloc.total < avail) alloc = alloc.with(u, alloc[u] + 1)
                    else {
                        // Pull one from the largest other category so "+" always works.
                        val donor = PrestigeUpgrade.entries.filter { it != u && alloc[it] > 0 }.maxByOrNull { alloc[it] }
                        if (donor != null) alloc = alloc.with(donor, alloc[donor] - 1).let { it.with(u, it[u] + 1) }
                    }
                    update()
                }
            }, LinearLayout.LayoutParams(dp(52), WRAP_CONTENT))
            box.addView(row)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Reset (Prestige)?")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Reset & spend") { _, _ ->
                if (progression.prestige(alloc)) {
                    Toast.makeText(
                        this,
                        "Prestige complete: damage ×%.2f · kill gold ×%.2f · wave gold ×%.2f".format(
                            progression.prestigeMultiplier, progression.prestigeKillGoldMultiplier, progression.prestigeWaveGoldMultiplier
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { enterImmersiveMode() }
            .create()
        confirm = { dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = alloc.total > 0 }
        dialog.setOnShowListener { confirm() }
        update()
        dialog.show()
    }
}
