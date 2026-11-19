package com.toasteralley.emberwatch.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.toasteralley.emberwatch.EmberwatchApp
import com.toasteralley.emberwatch.core.NumberFormat
import com.toasteralley.emberwatch.core.Palette

/**
 * Base for the progression menus (Workshop, Ultimate Weapons, Diamond Perks,
 * Enhancement, Research Bay, Store, Settings). Layout is built in code: a header
 * with balances and a scrolling list of rows; [build] repopulates the list.
 */
abstract class ListScreenActivity : AppCompatActivity() {
    protected val app get() = EmberwatchApp.instance
    protected val save get() = app.save
    protected lateinit var list: LinearLayout
    private lateinit var header: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    /** Override > 0 to rebuild periodically (e.g. research timers). */
    protected open val refreshMs: Long = 0
    private val ticker = object : Runnable {
        override fun run() { onTick(); build(); handler.postDelayed(this, refreshMs) }
    }

    abstract val screenTitle: String
    abstract fun buildRows()
    open fun onTick() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        edgeToEdge(root)
    }

    override fun onResume() {
        super.onResume()
        app.tickResearch()
        build()
        if (refreshMs > 0) handler.postDelayed(ticker, refreshMs)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker); app.persist() }

    fun build() {
        header.removeAllViews()
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(emberButton("‹ Back") { finish() })
            addView(label("  $screenTitle", 22f, Palette.CORAL, bold = true).apply { gravity = android.view.Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        })
        header.addView(label("Bounty ${NumberFormat.compact(save.bounty)}   ◆ ${save.diamonds}   Stones ${save.stones}   Cores ${save.cores}   Best wave ${save.bestWave}",
            13f, Palette.STEEL_LIGHT).apply { setPadding(0, dp(8), 0, dp(4)) })
        list.removeAllViews()
        buildRows()
    }

    protected fun section(text: String) { list.addView(label(text, 13f, Palette.TEXT_DIM, bold = true).apply { setPadding(0, dp(16), 0, 0) }) }
    protected fun note(text: String) { list.addView(label(text, 13f, Palette.TEXT_DIM).apply { setPadding(0, dp(8), 0, 0) }) }
    protected fun addRow(title: String, desc: String, action: String?, enabled: Boolean = true, onClick: () -> Unit = {}) =
        list.addView(row(title, desc, action, enabled) { onClick(); app.persist(); build() })
    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
