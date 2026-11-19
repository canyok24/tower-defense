package com.toasteralley.emberwatch.debug

import android.app.Activity

/** Release build: the management override does not exist. Every entry point is a no-op. */
object DebugTools {
    const val AVAILABLE = false
    @Suppress("UNUSED_PARAMETER") fun openMenu(activity: Activity) = Unit
    @Suppress("UNUSED_PARAMETER") fun openInGame(activity: Activity, hooks: InGameDebug) = hooks.resume()
}
