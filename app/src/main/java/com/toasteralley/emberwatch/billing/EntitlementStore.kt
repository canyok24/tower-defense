package com.toasteralley.emberwatch.billing

import android.content.Context

/**
 * Persists owned product ids. Lives in its own prefs file that no game reset
 * ever clears. Play remains the source of truth: BillingManager re-syncs on
 * every connection. Debug grants (management override) are stored separately
 * and ignored in release builds.
 */
class EntitlementStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("emberwatch_entitlements", Context.MODE_PRIVATE)

    fun loadPurchased(): Set<String> = prefs.getStringSet("owned", emptySet())!!.toSet()
    fun savePurchased(ids: Set<String>) { prefs.edit().putStringSet("owned", ids).apply() }
    fun loadDebug(): Set<String> = prefs.getStringSet("debug_owned", emptySet())!!.toSet()
    fun saveDebug(ids: Set<String>) { prefs.edit().putStringSet("debug_owned", ids).apply() }
}
