package com.toasteralley.towerdefense.billing

import android.content.Context
import androidx.core.content.edit

/** What the player owns. */
data class Entitlements(
    val removeAds: Boolean = false,
    val premiumTowers: Boolean = false,
)

/**
 * Persists entitlements in SharedPreferences so they are available instantly
 * on startup (and offline). Google Play remains the source of truth: the
 * [BillingManager] re-syncs this cache from Play on every connection.
 *
 * Debug grants are stored separately so a Play restore never wipes them and
 * they can never leak into a release build (see [BillingManager.debugGrant]).
 */
class EntitlementStore(context: Context) {
    private val prefs = context.getSharedPreferences("entitlements", Context.MODE_PRIVATE)

    fun loadPurchased() = Entitlements(
        removeAds = prefs.getBoolean(KEY_REMOVE_ADS, false),
        premiumTowers = prefs.getBoolean(KEY_PREMIUM, false),
    )

    fun savePurchased(e: Entitlements) = prefs.edit {
        putBoolean(KEY_REMOVE_ADS, e.removeAds)
        putBoolean(KEY_PREMIUM, e.premiumTowers)
    }

    fun loadDebug() = Entitlements(
        removeAds = prefs.getBoolean(KEY_DEBUG_REMOVE_ADS, false),
        premiumTowers = prefs.getBoolean(KEY_DEBUG_PREMIUM, false),
    )

    fun saveDebug(e: Entitlements) = prefs.edit {
        putBoolean(KEY_DEBUG_REMOVE_ADS, e.removeAds)
        putBoolean(KEY_DEBUG_PREMIUM, e.premiumTowers)
    }

    private companion object {
        const val KEY_REMOVE_ADS = "remove_ads"
        const val KEY_PREMIUM = "unlock_premium_towers"
        const val KEY_DEBUG_REMOVE_ADS = "debug_remove_ads"
        const val KEY_DEBUG_PREMIUM = "debug_unlock_premium_towers"
    }
}
