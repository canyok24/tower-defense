package com.toasteralley.emberwatch.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UnfetchedProduct
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryPurchasesAsync
import com.toasteralley.emberwatch.BuildConfig
import com.toasteralley.emberwatch.core.Products
import com.toasteralley.emberwatch.core.StonePacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Play Billing (Play Billing Library 8.3.0, billing-ktx).
 *
 * - pending one-time purchases enabled via [PendingPurchasesParams.Builder.enableOneTimeProducts]
 * - [BillingClient.Builder.enableAutoServiceReconnection] re-connects on demand
 * - queryProductDetailsAsync returns [QueryProductDetailsResult] (fetched + unfetched)
 * - owned items restored with queryPurchasesAsync; every purchase is acknowledged
 *
 * The five multiplier packs are one-time NON-consumables (never consumed), so they are
 * restored on reinstall / new devices. The Stone packs (core.StonePacks) are CONSUMABLES:
 * the Stones are granted first (idempotent by purchase token, see StonePacks.grant), the
 * save is persisted, and only then is the purchase consumed with consumePurchase (consumeAsync),
 * so a crash in between re-grants nothing and the next queryPurchases finishes the consume. Product ids must match Play Console exactly
 * (see core.Products). For production, verify purchase tokens server-side.
 */
class BillingManager(context: Context, private val store: EntitlementStore) :
    PurchasesUpdatedListener, BillingClientStateListener {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var purchased: Set<String> = store.loadPurchased()
    private var debugOwned: Set<String> = if (BuildConfig.DEBUG) store.loadDebug() else emptySet()

    private val _owned = MutableStateFlow(merge())
    /** Effective owned product ids (purchases + debug grants in debug builds only). */
    val owned: StateFlow<Set<String>> = _owned.asStateFlow()

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var reconnectDelayMs = INITIAL_RECONNECT_MS

    /**
     * Grants Stones for a consumable purchase token and persists; returns Stones granted
     * (0 when the token was already processed). Set by the Application.
     */
    var stoneGrant: ((purchaseToken: String, productIds: List<String>) -> Long)? = null

    fun startConnection() {
        if (billingClient.isReady || _connection.value == ConnectionState.CONNECTING) return
        _connection.value = ConnectionState.CONNECTING
        billingClient.startConnection(this)
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _connection.value = ConnectionState.CONNECTED
                reconnectDelayMs = INITIAL_RECONNECT_MS
                scope.launch { queryProductDetails(); restorePurchases() }
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> _connection.value = ConnectionState.UNAVAILABLE
            else -> { _connection.value = ConnectionState.DISCONNECTED; scheduleReconnect() }
        }
    }

    override fun onBillingServiceDisconnected() {
        _connection.value = ConnectionState.DISCONNECTED
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        handler.postDelayed({ startConnection() }, delay)
    }

    suspend fun queryProductDetails() {
        if (!billingClient.isReady) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList((Products.IDS + StonePacks.IDS).map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
            }).build()
        val (billingResult, result) = queryProductDetailsAsync(params)
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            result.unfetchedProductList.forEach { p: UnfetchedProduct ->
                Log.w(TAG, "Unfetched product ${p.productId} (status ${p.statusCode})")
            }
            _products.value = result.productDetailsList.associateBy { it.productId }
        } else Log.w(TAG, "queryProductDetails failed: ${billingResult.debugMessage}")
    }

    private suspend fun queryProductDetailsAsync(params: QueryProductDetailsParams): Pair<BillingResult, QueryProductDetailsResult> =
        suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { br, res -> if (cont.isActive) cont.resume(br to res) }
        }

    /** Localized price from Play, or null when the product isn't available. */
    fun priceOf(productId: String): String? = _products.value[productId]?.oneTimePurchaseOfferDetails?.formattedPrice

    fun launchPurchase(activity: Activity, productId: String): Boolean {
        if (!billingClient.isReady) {
            _messages.tryEmit("Google Play Billing is not connected yet. Try again in a moment.")
            startConnection(); return false
        }
        val details = _products.value[productId] ?: run {
            _messages.tryEmit("\"$productId\" is unavailable. Purchases need the app on a Play Console track with this product active.")
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()))
            .build()
        val r = billingClient.launchBillingFlow(activity, params)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) {
            _messages.tryEmit("Could not start purchase (${r.responseCode}): ${r.debugMessage}"); return false
        }
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { processPurchases(purchases.orEmpty(), fullSync = false) }
            BillingClient.BillingResponseCode.USER_CANCELED -> _messages.tryEmit("Purchase cancelled")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _messages.tryEmit("Already owned - restoring"); scope.launch { restorePurchases() }
            }
            else -> _messages.tryEmit("Purchase failed (${result.responseCode}): ${result.debugMessage}")
        }
    }

    suspend fun restorePurchases(userInitiated: Boolean = false) {
        if (!billingClient.isReady) {
            if (userInitiated) _messages.tryEmit("Not connected to Google Play yet")
            startConnection(); return
        }
        val r = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        )
        if (r.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            processPurchases(r.purchasesList, fullSync = true)
            if (userInitiated) _messages.tryEmit("Purchases restored")
        } else if (userInitiated) _messages.tryEmit("Restore failed: ${r.billingResult.debugMessage}")
    }

    fun restorePurchasesAsync(userInitiated: Boolean = false) { scope.launch { restorePurchases(userInitiated) } }

    /** [fullSync]: the list is Play's complete owned set, so missing ids (refunds) are revoked. */
    private suspend fun processPurchases(purchases: List<Purchase>, fullSync: Boolean) {
        val next = if (fullSync) mutableSetOf() else purchased.toMutableSet()
        for (p in purchases) when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                // TODO(production): verify p.originalJson / signature on your server.
                if (p.products.any { it in StonePacks.IDS }) {
                    val granted = stoneGrant?.invoke(p.purchaseToken, p.products) ?: continue   // not wired: retry later
                    if (granted > 0) _messages.tryEmit("Stones +$granted")
                    consume(p)
                    continue
                }
                p.products.filter { it in Products.IDS }.forEach { next += it }
                if (!p.isAcknowledged) acknowledge(p)
                if (!fullSync) _messages.tryEmit("Thank you! Purchase complete.")
            }
            Purchase.PurchaseState.PENDING ->
                if (!fullSync) _messages.tryEmit("Purchase pending - it unlocks once payment completes.")
            else -> Unit
        }
        purchased = next
        store.savePurchased(purchased)
        _owned.value = merge()
    }

    private suspend fun consume(p: Purchase) {
        val r = billingClient.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
        if (r.billingResult.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "Consume failed: ${r.billingResult.debugMessage}")
    }

    private suspend fun acknowledge(p: Purchase) {
        val r = billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
        if (r.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "Acknowledge failed: ${r.debugMessage}")
    }

    /** Management override (debug builds only): locally grant/revoke a product. */
    fun debugGrant(productId: String, granted: Boolean) {
        if (!BuildConfig.DEBUG) return
        debugOwned = if (granted) debugOwned + productId else debugOwned - productId
        store.saveDebug(debugOwned)
        _owned.value = merge()
    }

    fun isPurchased(productId: String) = productId in purchased

    private fun merge(): Set<String> = purchased + debugOwned

    companion object {
        private const val TAG = "BillingManager"
        private const val INITIAL_RECONNECT_MS = 1_000L
        private const val MAX_RECONNECT_MS = 60_000L
    }
}
