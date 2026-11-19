package com.toasteralley.emberwatch.core

/**
 * One-time (non-consumable) Play products. Prices are configured in Play Console;
 * [listPrice] is only the suggested USD price for the README / fallback label.
 * The Store always shows ProductDetails.formattedPrice when available.
 */
data class Product(
    val id: String,
    val title: String,
    val listPrice: String,
    val coinMultiplier: Double,
    val removesAds: Boolean = false,
)

object Products {
    val NO_ADS = Product("no_ads", "No Ads", "$4.99", 1.5, removesAds = true)
    val EPIC = Product("pack_epic", "Epic Pack", "$19.99", 2.0)
    val TIER3 = Product("pack_tier3", "Tier 3 Pack", "$39.99", 3.0)
    val TIER4 = Product("pack_tier4", "Tier 4 Pack", "$149.99", 4.0)
    val TIER5 = Product("pack_tier5", "Tier 5 Pack", "$499.99", 10.0)
    val ALL = listOf(NO_ADS, EPIC, TIER3, TIER4, TIER5)
    val IDS = ALL.map { it.id }
    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
