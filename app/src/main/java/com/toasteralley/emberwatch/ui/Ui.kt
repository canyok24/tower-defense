package com.toasteralley.emberwatch.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.toasteralley.emberwatch.core.Palette

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

/** Edge-to-edge with the content padded by system bars + display cutout. */
fun Activity.edgeToEdge(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom)
        insets
    }
}

fun roundedBg(color: Int, strokeColor: Int, radius: Float, strokePx: Int = 2) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; setStroke(strokePx, strokeColor)
}

fun Context.label(text: CharSequence, sizeSp: Float = 15f, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = sizeSp; setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.emberButton(text: CharSequence, enabled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 15f
    isEnabled = enabled
    setTextColor(if (enabled) Palette.TEXT else Palette.TEXT_DIM)
    background = roundedBg(if (enabled) 0xFF22384A.toInt() else Palette.BG_PANEL, if (enabled) Palette.STEEL_LIGHT else Palette.STEEL, dp(10).toFloat())
    setPadding(dp(14), dp(8), dp(14), dp(8))
    minHeight = dp(44); minimumHeight = dp(44)
    setOnClickListener { onClick() }
}

/** A card row: title + description on the left, an action button on the right. */
fun Context.row(title: String, desc: String, action: String?, enabled: Boolean, onClick: () -> Unit): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = roundedBg(Palette.BG_PANEL, 0xFF223040.toInt(), dp(12).toFloat())
        setPadding(dp(14), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 16f, Palette.TEXT, bold = true))
            addView(label(desc, 13f, Palette.TEXT_DIM))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) addView(emberButton(action, enabled, onClick), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
    }
