package com.toasteralley.emberwatch.debug

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.toasteralley.emberwatch.BuildConfig

/**
 * DEBUG SOURCE SET ONLY - management override (developer/admin testing tool).
 * This file does not exist in release builds (src/release has a no-op stub).
 */
object DebugTools {
    val AVAILABLE: Boolean get() = BuildConfig.DEBUG

    fun openMenu(activity: Activity) {
        if (!BuildConfig.DEBUG) return
        activity.startActivity(Intent(activity, DebugMenuActivity::class.java))
    }

    /** In-run override dialog (opened via hold-pause-2s + tap WAVE label 5x). */
    fun openInGame(activity: Activity, hooks: InGameDebug) {
        if (!BuildConfig.DEBUG) { hooks.resume(); return }
        val items = arrayOf("+1 wave", "+10 waves", "Jump to wave N…", "Force-spawn Spiral Boss",
            "God mode: ${if (hooks.godMode()) "ON → turn off" else "OFF → turn on"}", "+1M cash", "Full heal")
        AlertDialog.Builder(activity).setTitle("Management override")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> hooks.skipWaves(1)
                    1 -> hooks.skipWaves(10)
                    2 -> { askNumber(activity, "Jump to wave") { hooks.jumpToWave(it.toInt()); hooks.resume() }; return@setItems }
                    3 -> hooks.spawnBoss()
                    4 -> hooks.setGodMode(!hooks.godMode())
                    5 -> hooks.addCash(1_000_000.0)
                    6 -> hooks.heal()
                }
                hooks.resume()
            }
            .setOnCancelListener { hooks.resume() }
            .show()
    }

    fun askNumber(activity: Activity, title: String, onValue: (Double) -> Unit) {
        val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        AlertDialog.Builder(activity).setTitle(title).setView(input)
            .setPositiveButton("Set") { _, _ -> input.text.toString().toDoubleOrNull()?.let(onValue) }
            .setNegativeButton("Cancel", null).show()
    }
}
