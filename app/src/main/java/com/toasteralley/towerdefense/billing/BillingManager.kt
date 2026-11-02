package com.toasteralley.towerdefense.billing

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
import com.toasteralley.towerdefense.BuildConfig
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
 * Google Play Billing (Play Billing Library 8, billing-ktx) wrapper.
 *
 * PBL 8 notes: pending purchases are enabled via [PendingPurchasesParams]
 * (one-time products), the client reconnects automatically
 * ([BillingClient.Builder.enableAutoServiceReconnection]), product queries return
 * [QueryProductDetailsResult] (fetched + unfetched products), and owned items are
 * restored with queryPurchasesAsync (queryPurchaseHistory was removed in PBL 8).
 *
 * Responsibilities:
 *  - connect to Play (with exponential-backoff reconnects)
 *  - query [ProductDetails] for the in-app products
 *  - launch the purchase flow
 *  - acknowledge purchases (unacknowledged purchases are refunded by Play after 3 days)
 *  - restore purchases on startup / resume and persist entitlements
 *
 * IMPORTANT: real purchases only work when this app (same applicationId,
 * signed with a key Play recognises) has been uploaded to a Play Console track
 * and in-app products with the exact IDs [PRODUCT_REMOVE_ADS] and
 * [PRODUCT_PREMIUM_TOWERS] are created and active. Otherwise product queries
 * return an empty list and the Store shows the products as unavailable.
 *
 * NOTE: For production you should also verify purchase tokens on a backend
 * server (Google Play Developer API) before granting entitlements.
 */
class BillingManager(
    context: Context,
    private val store: EntitlementStore,
) : PurchasesUpdatedListener, BillingClientStateListener {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        // Required in PBL 8: one-time-product pending purchases are opt-in via params.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        // PBL 8: the library re-establishes a lost connection automatically when an
        // API call is made, so we only need a manual retry for the initial setup.
        .enableAutoServiceReconnection()
        .build()

    private var purchased = store.loadPurchased()
    private var debug = if (BuildConfig.DEBUG) store.loadDebug() else Entitlements()

    private val _entitlements = MutableStateFlow(merge())
    /** Effective entitlements (purchases + debug grants in debug builds). */
    val entitlements: StateFlow<Entitlements> = _entitlements.asStateFlow()

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off user-facing messages (purchase results, errors). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var reconnectDelayMs = INITIAL_RECONNECT_MS

    // ------------------------------------------------------------ connection

    fun startConnection() {
        if (billingClient.isReady || _connection.value == ConnectionState.CONNECTING) return
        _connection.value = ConnectionState.CONNECTING
        Log.d(TAG, "Connecting to Google Play Billing")
        billingClient.startConnection(this)
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        Log.d(TAG, "Setup finished: ${result.responseCode} ${result.debugMessage}")
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _connection.value = ConnectionState.CONNECTED
                reconnectDelayMs = INITIAL_RECONNECT_MS
                scope.launch {
                    queryProductDetails()
                    restorePurchases()
                }
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                // e.g. no Play Store / account on this device or emulator.
                _connection.value = ConnectionState.UNAVAILABLE
            }
            else -> {
                _connection.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    override fun onBillingServiceDisconnected() {
        // With enableAutoServiceReconnection() the next API call reconnects for us;
        // we still schedule a backed-off reconnect so prices/purchases refresh.
        Log.d(TAG, "Billing service disconnected")
        _connection.value = ConnectionState.DISCONNECTED
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        handler.postDelayed({ startConnection() }, delay)
    }

    // ------------------------------------------------------------ products

    suspend fun queryProductDetails() {
        if (!billingClient.isReady) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ALL_PRODUCTS.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()
        val (billingResult, result) = queryProductDetailsAsync(params)
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val list = result.productDetailsList
            Log.d(TAG, "Product details: ${list.map { it.productId }}")
            // PBL 8: products that couldn't be fetched (e.g. not created/active in
            // Play Console, or not available to this user) are reported separately.
            result.unfetchedProductList.forEach { p: UnfetchedProduct ->
                Log.w(TAG, "Unfetched product ${p.productId} (status ${p.statusCode})")
            }
            _products.value = list.associateBy { it.productId }
        } else {
            Log.w(TAG, "queryProductDetails failed: ${billingResult.debugMessage}")
        }
    }

    /**
     * Coroutine wrapper around PBL 8's queryProductDetailsAsync, which returns a
     * [QueryProductDetailsResult] (the billing-ktx extension drops the unfetched list).
     */
    private suspend fun queryProductDetailsAsync(
        params: QueryProductDetailsParams,
    ): Pair<BillingResult, QueryProductDetailsResult> = suspendCancellableCoroutine { cont ->
        billingClient.queryProductDetailsAsync(params) { billingResult, result ->
            if (cont.isActive) cont.resume(billingResult to result)
        }
    }

    /** Localized price string, or null if the product isn't available. */
    fun priceOf(productId: String): String? =
        _products.value[productId]?.oneTimePurchaseOfferDetails?.formattedPrice

    // ------------------------------------------------------------ purchasing

    /** Starts the Google Play purchase UI. Returns false if it could not start. */
    fun launchPurchase(activity: Activity, productId: String): Boolean {
        if (!billingClient.isReady) {
            _messages.tryEmit("Google Play Billing is not connected. Check your Play Store account and try again.")
            startConnection()
            return false
        }
        val details = _products.value[productId]
        if (details == null) {
            _messages.tryEmit(
                "Product \"$productId\" is unavailable. Real purchases require the app to be " +
                    "uploaded to Play Console with this product ID."
            )
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
            )
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _messages.tryEmit("Could not start purchase (${result.responseCode}): ${result.debugMessage}")
            return false
        }
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                scope.launch { processPurchases(purchases.orEmpty(), fullSync = false) }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                _messages.tryEmit("Purchase cancelled")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _messages.tryEmit("You already own this item - restoring")
                scope.launch { restorePurchases() }
            }
            else -> _messages.tryEmit("Purchase failed (${result.responseCode}): ${result.debugMessage}")
        }
    }

    /** Re-reads owned purchases from Play (call on startup and onResume). */
    suspend fun restorePurchases(userInitiated: Boolean = false) {
        if (!billingClient.isReady) {
            if (userInitiated) _messages.tryEmit("Not connected to Google Play yet")
            startConnection()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            processPurchases(result.purchasesList, fullSync = true)
            if (userInitiated) _messages.tryEmit("Purchases restored")
        } else if (userInitiated) {
            _messages.tryEmit("Restore failed: ${result.billingResult.debugMessage}")
        }
    }

    fun restorePurchasesAsync(userInitiated: Boolean = false) {
        scope.launch { restorePurchases(userInitiated) }
    }

    /**
     * @param fullSync true when [purchases] is the complete owned list from Play,
     *   so anything missing (e.g. refunded) is revoked.
     */
    private suspend fun processPurchases(purchases: List<Purchase>, fullSync: Boolean) {
        var removeAds = if (fullSync) false else purchased.removeAds
        var premium = if (fullSync) false else purchased.premiumTowers

        for (purchase in purchases) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    // TODO(production): verify purchase.originalJson / signature on your server.
                    if (PRODUCT_REMOVE_ADS in purchase.products) removeAds = true
                    if (PRODUCT_PREMIUM_TOWERS in purchase.products) premium = true
                    if (!purchase.isAcknowledged) acknowledge(purchase)
                    if (!fullSync) _messages.tryEmit("Thank you! Purchase complete.")
                }
                Purchase.PurchaseState.PENDING ->
                    if (!fullSync) _messages.tryEmit("Purchase pending - it will unlock once payment completes.")
                else -> Unit
            }
        }
        purchased = Entitlements(removeAds = removeAds, premiumTowers = premium)
        store.savePurchased(purchased)
        _entitlements.value = merge()
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "Acknowledge failed: ${result.debugMessage}")
        }
    }

    // ------------------------------------------------------------ debug

    /**
     * Debug builds only: locally grant/revoke an entitlement so the premium
     * towers and ad removal can be tested without Play Console. No-op in release.
     */
    fun debugGrant(productId: String, granted: Boolean) {
        if (!BuildConfig.DEBUG) return
        debug = when (productId) {
            PRODUCT_REMOVE_ADS -> debug.copy(removeAds = granted)
            PRODUCT_PREMIUM_TOWERS -> debug.copy(premiumTowers = granted)
            else -> debug
        }
        store.saveDebug(debug)
        _entitlements.value = merge()
    }

    fun isDebugGranted(productId: String): Boolean = when (productId) {
        PRODUCT_REMOVE_ADS -> debug.removeAds
        PRODUCT_PREMIUM_TOWERS -> debug.premiumTowers
        else -> false
    }

    private fun merge() = Entitlements(
        removeAds = purchased.removeAds || debug.removeAds,
        premiumTowers = purchased.premiumTowers || debug.premiumTowers,
    )

    companion object {
        private const val TAG = "BillingManager"
        private const val INITIAL_RECONNECT_MS = 1_000L
        private const val MAX_RECONNECT_MS = 60_000L

        /** Must match the in-app product IDs created in Play Console exactly. */
        const val PRODUCT_REMOVE_ADS = "remove_ads"
        const val PRODUCT_PREMIUM_TOWERS = "unlock_premium_towers"
        val ALL_PRODUCTS = listOf(PRODUCT_REMOVE_ADS, PRODUCT_PREMIUM_TOWERS)
    }
}
