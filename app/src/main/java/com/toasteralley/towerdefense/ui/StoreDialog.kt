package com.toasteralley.towerdefense.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toasteralley.towerdefense.BuildConfig
import com.toasteralley.towerdefense.app
import com.toasteralley.towerdefense.billing.BillingManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * In-app Store. Lists the two non-consumable products with live prices from
 * Google Play, lets the player buy or restore, and (debug builds only) offers
 * local grant toggles for testing without Play Console.
 */
object StoreDialog {

    private data class Item(val productId: String, val title: String, val description: String)

    private val ITEMS = listOf(
        Item(BillingManager.PRODUCT_REMOVE_ADS, "Remove Ads", "Hide all banner ads forever."),
        Item(
            BillingManager.PRODUCT_PREMIUM_TOWERS, "Premium Towers",
            "Unlock the Sniper (long range, heavy damage) and Laser (rapid beam) towers."
        ),
    )

    fun show(activity: AppCompatActivity, onDismiss: () -> Unit = {}) {
        val billing = activity.app.billing
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val status = TextView(activity).apply { textSize = 12f; setTextColor(Color.GRAY) }
        root.addView(status)

        class Row(val item: Item, val price: TextView, val buy: Button, val debug: Button?)

        val rows = ITEMS.map { item ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(4))
            }
            card.addView(TextView(activity).apply {
                text = item.title; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(TextView(activity).apply { text = item.description; textSize = 14f })
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val price = TextView(activity).apply {
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val buy = Button(activity).apply {
                setOnClickListener { billing.launchPurchase(activity, item.productId) }
            }
            line.addView(price); line.addView(buy)
            card.addView(line)
            val debugBtn = if (BuildConfig.DEBUG) Button(activity).apply {
                textSize = 11f
                setOnClickListener {
                    billing.debugGrant(item.productId, !billing.isDebugGranted(item.productId))
                }
            }.also { card.addView(it) } else null
            root.addView(card)
            Row(item, price, buy, debugBtn)
        }

        root.addView(Button(activity).apply {
            text = "Restore purchases"
            setOnClickListener { billing.restorePurchasesAsync(userInitiated = true) }
        })
        root.addView(TextView(activity).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, 0)
            text = "Note: real purchases only work after the app is uploaded to Google Play Console " +
                "(any testing track) with in-app products whose IDs exactly match " +
                "\"${BillingManager.PRODUCT_REMOVE_ADS}\" and \"${BillingManager.PRODUCT_PREMIUM_TOWERS}\", " +
                "and the device is signed in with a licensed tester account." +
                if (BuildConfig.DEBUG) " Debug builds show local test toggles." else ""
        })

        val scroll = ScrollView(activity).apply {
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Store")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()

        val job = activity.lifecycleScope.launch {
            combine(billing.entitlements, billing.products, billing.connection) { e, p, c -> Triple(e, p, c) }
                .collect { (ent, products, conn) ->
                    status.text = when (conn) {
                        BillingManager.ConnectionState.CONNECTED ->
                            if (products.isEmpty()) "Connected to Google Play - no products found (see note below)"
                            else "Connected to Google Play"
                        BillingManager.ConnectionState.CONNECTING -> "Connecting to Google Play…"
                        BillingManager.ConnectionState.UNAVAILABLE -> "Google Play Billing unavailable on this device"
                        BillingManager.ConnectionState.DISCONNECTED -> "Not connected to Google Play"
                    }
                    for (row in rows) {
                        val owned = when (row.item.productId) {
                            BillingManager.PRODUCT_REMOVE_ADS -> ent.removeAds
                            else -> ent.premiumTowers
                        }
                        val price = products[row.item.productId]?.oneTimePurchaseOfferDetails?.formattedPrice
                        row.price.text = when {
                            owned -> "Owned ✓"
                            price != null -> price
                            else -> "Unavailable"
                        }
                        row.buy.text = if (owned) "Owned" else "Buy"
                        row.buy.isEnabled = !owned && price != null
                        row.debug?.text = if (billing.isDebugGranted(row.item.productId))
                            "[Debug] Revoke local grant" else "[Debug] Grant locally"
                    }
                }
        }
        dialog.setOnDismissListener {
            job.cancel()
            onDismiss()
        }
        dialog.show()
    }
}
