package com.tunemind.app.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.android.billingclient.api.QueryPurchasesParams
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.domain.network.BackendEndpointManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class TuneMindTier {
    FREE,
    TRIAL,
    PRO
}

data class PlayPlanUiModel(
    val productId: String,
    val title: String,
    val periodLabel: String,
    val formattedPrice: String,
    val badge: String,
    val subtext: String,
    val hasFreeTrialOffer: Boolean = true,
    val isPlayProductReady: Boolean = false
)

data class BillingUiState(
    val tier: TuneMindTier = TuneMindTier.FREE,
    val hasProAccess: Boolean = false,
    val isTrial: Boolean = false,
    val activePlanId: String? = null,
    val trialDaysRemaining: Int = 0,
    val dailyAgentLimit: Int = 0,
    val dailyAgentUsed: Int = 0,
    val dailyAgentRemaining: Int = 0,
    val dailyForYouRefreshLimit: Int = 0,
    val dailyForYouRefreshesUsed: Int = 0,
    val freeForYouDailyPicks: Int = 2,
    val autoRenewing: Boolean = false,
    val isLoading: Boolean = false,
    val isPlayBillingReady: Boolean = false,
    val statusMessage: String? = null,
    val plans: List<PlayPlanUiModel> = defaultFallbackPlans()
)

private fun isEastAfricaLocale(): Boolean {
    val country = Locale.getDefault().country.uppercase(Locale.US)
    return country in setOf("KE", "TZ", "UG", "RW", "BI")
}

fun defaultFallbackPlans(): List<PlayPlanUiModel> {
    val eastAfrica = isEastAfricaLocale()
    return listOf(
        PlayPlanUiModel(
            productId = TuneMindBillingManager.PRODUCT_ANNUAL,
            title = "Yearly",
            periodLabel = "per year",
            formattedPrice = if (eastAfrica) "KES 1,800" else "$29.99",
            badge = "Best Value · 7 Days Free",
            subtext = if (eastAfrica) "7 days free, then KES 1,800/yr · Save 40%" else "7 days free, then $29.99/yr · Save 50%",
            hasFreeTrialOffer = true,
            isPlayProductReady = false
        ),
        PlayPlanUiModel(
            productId = TuneMindBillingManager.PRODUCT_MONTHLY,
            title = "Monthly",
            periodLabel = "per month",
            formattedPrice = if (eastAfrica) "KES 250" else "$4.99",
            badge = "7 Days Free",
            subtext = if (eastAfrica) "7 days free, then KES 250/mo" else "7 days free, then $4.99/mo",
            hasFreeTrialOffer = true,
            isPlayProductReady = false
        )
    )
}

/**
 * Google Play Billing v7 + Backend Entitlement Manager for TuneMind Pro.
 *
 * Security & Cost Control:
 * - Never trusts client-side booleans for paid Claude AI endpoints; always verifies
 *   Google Play purchase tokens server-side via `/api/v1/billing/google-play/verify`.
 * - Acknowledges purchases immediately after verification to prevent Google Play auto-refunds.
 */
class TuneMindBillingManager private constructor(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadCachedState())
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    @Volatile
    private var productDetailsMap: Map<String, ProductDetails> = emptyMap()

    @Volatile
    private var isConnecting = false

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(appContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()
    }

    fun initialize() {
        ensurePlayConnection()
        scope.launch {
            refreshEntitlementFromBackend()
        }
    }

    fun clearStatusMessage() {
        _state.value = _state.value.copy(statusMessage = null)
    }

    private fun ensurePlayConnection(onConnected: (() -> Unit)? = null) {
        if (billingClient.isReady) {
            onConnected?.invoke()
            return
        }
        if (isConnecting) return
        isConnecting = true

        runCatching {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    isConnecting = false
                    val ready = result.responseCode == BillingClient.BillingResponseCode.OK
                    _state.value = _state.value.copy(isPlayBillingReady = ready)
                    if (ready) {
                        queryPlayProducts()
                        queryAndRestoreActivePurchases(silent = true)
                        onConnected?.invoke()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    isConnecting = false
                    _state.value = _state.value.copy(isPlayBillingReady = false)
                }
            })
        }.onFailure {
            isConnecting = false
        }
    }

    private fun queryPlayProducts() {
        if (!billingClient.isReady) return
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ANNUAL)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, detailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                val map = detailsList.associateBy { it.productId }
                productDetailsMap = map
                val updatedPlans = buildPlansFromPlayDetails(map)
                _state.value = _state.value.copy(plans = updatedPlans)
            }
        }
    }

    private fun buildPlansFromPlayDetails(detailsMap: Map<String, ProductDetails>): List<PlayPlanUiModel> {
        val defaults = defaultFallbackPlans().associateBy { it.productId }
        val orderedIds = listOf(PRODUCT_ANNUAL, PRODUCT_MONTHLY)

        return orderedIds.mapNotNull { pid ->
            val fallback = defaults[pid] ?: return@mapNotNull null
            val pd = detailsMap[pid] ?: return@mapNotNull fallback
            val offers = pd.subscriptionOfferDetails.orEmpty()
            val chosenOffer = pickBestOffer(offers)
            val phases = chosenOffer?.pricingPhases?.pricingPhaseList.orEmpty()
            val paidPhase = phases.lastOrNull { it.priceAmountMicros > 0L } ?: phases.lastOrNull()
            val hasTrial = phases.any { it.priceAmountMicros == 0L }
            val livePrice = paidPhase?.formattedPrice?.takeIf { it.isNotBlank() } ?: fallback.formattedPrice
            val isYearly = pid == PRODUCT_ANNUAL

            PlayPlanUiModel(
                productId = pid,
                title = if (isYearly) "Yearly" else "Monthly",
                periodLabel = if (isYearly) "per year" else "per month",
                formattedPrice = livePrice,
                badge = if (isYearly) {
                    if (hasTrial) "Best Value · 7 Days Free" else "Best Value"
                } else {
                    if (hasTrial) "7 Days Free" else "Flexible"
                },
                subtext = if (hasTrial) {
                    "7 days free, then $livePrice${if (isYearly) "/yr" else "/mo"}"
                } else {
                    "Billed $livePrice${if (isYearly) "/yr" else "/mo"}"
                },
                hasFreeTrialOffer = hasTrial,
                isPlayProductReady = chosenOffer != null
            )
        }
    }

    private fun pickBestOffer(
        offers: List<ProductDetails.SubscriptionOfferDetails>
    ): ProductDetails.SubscriptionOfferDetails? {
        if (offers.isEmpty()) return null
        return offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: offers.firstOrNull()
    }

    fun launchSubscriptionPurchase(activity: Activity, productId: String) {
        if (!billingClient.isReady) {
            _state.value = _state.value.copy(
                statusMessage = "Connecting to Google Play Store... please try again in a moment."
            )
            ensurePlayConnection()
            return
        }

        val details = productDetailsMap[productId]
        val offer = details?.subscriptionOfferDetails?.let { pickBestOffer(it) }
        if (details == null || offer == null) {
            _state.value = _state.value.copy(
                statusMessage = "This subscription plan is still syncing with Google Play Console. Make sure '$productId' is active in Play Console."
            )
            queryPlayProducts()
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.value = _state.value.copy(
                statusMessage = "Google Play checkout couldn't open (${result.debugMessage.ifBlank { "code ${result.responseCode}" }})."
            )
        }
    }

    fun restorePurchases() {
        _state.value = _state.value.copy(isLoading = true, statusMessage = null)
        if (!billingClient.isReady) {
            ensurePlayConnection {
                queryAndRestoreActivePurchases(silent = false)
            }
            scope.launch {
                refreshEntitlementFromBackend()
                _state.value = _state.value.copy(isLoading = false)
            }
            return
        }
        queryAndRestoreActivePurchases(silent = false)
    }

    private fun queryAndRestoreActivePurchases(silent: Boolean) {
        if (!billingClient.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                if (!silent) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        statusMessage = "Could not query Google Play purchases right now."
                    )
                }
                return@queryPurchasesAsync
            }

            val purchased = purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (purchased.isEmpty()) {
                scope.launch {
                    refreshEntitlementFromBackend()
                    if (!silent) {
                        val hasPro = _state.value.hasProAccess
                        _state.value = _state.value.copy(
                            isLoading = false,
                            statusMessage = if (hasPro) "Subscription restored." else "No active Google Play subscription found for this account."
                        )
                    }
                }
                return@queryPurchasesAsync
            }

            scope.launch {
                var restoredAny = false
                for (purchase in purchased) {
                    val ok = verifyAndAcknowledgePurchase(purchase)
                    if (ok) restoredAny = true
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = if (!silent) {
                        if (restoredAny) "TuneMind Pro restored successfully." else "Could not verify active Google Play subscription."
                    } else _state.value.statusMessage
                )
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                if (list.isEmpty()) return
                _state.value = _state.value.copy(isLoading = true, statusMessage = null)
                scope.launch {
                    var anySuccess = false
                    for (purchase in list) {
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            if (verifyAndAcknowledgePurchase(purchase)) {
                                anySuccess = true
                            }
                        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                            _state.value = _state.value.copy(
                                isLoading = false,
                                statusMessage = "Payment pending in Google Play. Access unlocks automatically once confirmed."
                            )
                        }
                    }
                    _state.value = _state.value.copy(
                        isLoading = false,
                        statusMessage = if (anySuccess) "7-day free trial activated! Enjoy TuneMind Pro." else _state.value.statusMessage
                    )
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = null
                )
            }
            else -> {
                _state.value = _state.value.copy(
                    isLoading = false,
                    statusMessage = "Purchase could not be completed (Google Play code ${billingResult.responseCode})."
                )
            }
        }
    }

    private suspend fun verifyAndAcknowledgePurchase(purchase: Purchase): Boolean {
        val productId = purchase.products.firstOrNull() ?: return false
        val verifiedOnBackend = verifyPurchaseWithBackend(
            productId = productId,
            purchaseToken = purchase.purchaseToken,
            orderId = purchase.orderId
        )

        if (verifiedOnBackend && !purchase.isAcknowledged) {
            withContext(Dispatchers.Main) {
                runCatching {
                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(ackParams) { ackRes ->
                        if (ackRes.responseCode != BillingClient.BillingResponseCode.OK) {
                            Log.w(TAG, "Acknowledge notice: ${ackRes.responseCode} ${ackRes.debugMessage}")
                        }
                    }
                }
            }
        }
        return verifiedOnBackend
    }

    private suspend fun verifyPurchaseWithBackend(
        productId: String,
        purchaseToken: String,
        orderId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appContext).trimEnd('/')
            val authSession = TuneMindAuthSessionManager.getInstance(appContext).sessionFlow.value
            val bodyJson = JSONObject().apply {
                put("userId", authSession.userId)
                authSession.email?.let { put("userEmail", it) }
                put("productId", productId)
                put("purchaseToken", purchaseToken)
                put("packageName", appContext.packageName)
                if (!orderId.isNullOrBlank()) put("orderId", orderId)
            }
            val reqBuilder = Request.Builder()
                .url("$baseUrl/api/v1/billing/google-play/verify")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            val accessToken = TuneMindAuthSessionManager.getInstance(appContext).getAccessToken()
            if (accessToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $accessToken")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || text.isBlank()) return@withContext false
                val root = JSONObject(text)
                val entJson = root.optJSONObject("entitlement")
                if (entJson != null) {
                    applyEntitlementJson(entJson)
                }
                root.optBoolean("verified", false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend Play verification error: ${e.message}")
            false
        }
    }

    suspend fun refreshEntitlementFromBackend(): BillingUiState = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appContext).trimEnd('/')
            val authMgr = TuneMindAuthSessionManager.getInstance(appContext)
            val authSession = authMgr.sessionFlow.value
            val reqBuilder = Request.Builder()
                .url("$baseUrl/api/v1/billing/entitlement?userId=${Uri.encode(authSession.userId)}")
                .get()

            val accessToken = authMgr.getAccessToken()
            if (accessToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $accessToken")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful && text.isNotBlank()) {
                    val root = JSONObject(text)
                    val entJson = root.optJSONObject("entitlement")
                    if (entJson != null) {
                        applyEntitlementJson(entJson)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Billing entitlement refresh skipped: ${e.message}")
        }
        _state.value
    }

    fun applyEntitlementJson(entJson: JSONObject) {
        val tierStr = entJson.optString("tier", "FREE").uppercase(Locale.US)
        val tier = when (tierStr) {
            "PRO" -> TuneMindTier.PRO
            "TRIAL" -> TuneMindTier.TRIAL
            else -> TuneMindTier.FREE
        }
        val next = _state.value.copy(
            tier = tier,
            hasProAccess = entJson.optBoolean("hasProAccess", tier != TuneMindTier.FREE),
            isTrial = entJson.optBoolean("isTrial", tier == TuneMindTier.TRIAL),
            activePlanId = entJson.optString("planId", "").takeIf { it.isNotBlank() && it != "null" },
            trialDaysRemaining = entJson.optInt("trialDaysRemaining", 0),
            dailyAgentLimit = entJson.optInt("dailyAgentLimit", 0),
            dailyAgentUsed = entJson.optInt("dailyAgentUsed", 0),
            dailyAgentRemaining = entJson.optInt("dailyAgentRemaining", 0),
            dailyForYouRefreshLimit = entJson.optInt("dailyForYouRefreshLimit", 0),
            dailyForYouRefreshesUsed = entJson.optInt("dailyForYouRefreshesUsed", 0),
            freeForYouDailyPicks = entJson.optInt("freeForYouDailyPicks", 2),
            autoRenewing = entJson.optBoolean("autoRenewing", false)
        )
        _state.value = next
        saveCachedState(next)
    }

    fun openManageSubscriptionsInPlayStore(activityContext: Context) {
        val sku = _state.value.activePlanId ?: PRODUCT_ANNUAL
        val url = "https://play.google.com/store/account/subscriptions?sku=${Uri.encode(sku)}&package=${Uri.encode(appContext.packageName)}"
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        }
    }

    private fun saveCachedState(s: BillingUiState) {
        runCatching {
            val obj = JSONObject().apply {
                put("tier", s.tier.name)
                put("hasProAccess", s.hasProAccess)
                put("isTrial", s.isTrial)
                put("planId", s.activePlanId ?: "")
                put("trialDaysRemaining", s.trialDaysRemaining)
                put("dailyAgentLimit", s.dailyAgentLimit)
                put("dailyAgentUsed", s.dailyAgentUsed)
                put("dailyAgentRemaining", s.dailyAgentRemaining)
                put("dailyForYouRefreshLimit", s.dailyForYouRefreshLimit)
                put("dailyForYouRefreshesUsed", s.dailyForYouRefreshesUsed)
                put("freeForYouDailyPicks", s.freeForYouDailyPicks)
                put("autoRenewing", s.autoRenewing)
            }
            prefs.edit().putString(KEY_CACHED_ENTITLEMENT, obj.toString()).apply()
        }
    }

    private fun loadCachedState(): BillingUiState {
        return runCatching {
            val raw = prefs.getString(KEY_CACHED_ENTITLEMENT, null) ?: return BillingUiState()
            val obj = JSONObject(raw)
            val tierStr = obj.optString("tier", "FREE")
            val tier = runCatching { TuneMindTier.valueOf(tierStr) }.getOrDefault(TuneMindTier.FREE)
            BillingUiState(
                tier = tier,
                hasProAccess = obj.optBoolean("hasProAccess", tier != TuneMindTier.FREE),
                isTrial = obj.optBoolean("isTrial", tier == TuneMindTier.TRIAL),
                activePlanId = obj.optString("planId", "").takeIf { it.isNotBlank() },
                trialDaysRemaining = obj.optInt("trialDaysRemaining", 0),
                dailyAgentLimit = obj.optInt("dailyAgentLimit", 0),
                dailyAgentUsed = obj.optInt("dailyAgentUsed", 0),
                dailyAgentRemaining = obj.optInt("dailyAgentRemaining", 0),
                dailyForYouRefreshLimit = obj.optInt("dailyForYouRefreshLimit", 0),
                dailyForYouRefreshesUsed = obj.optInt("dailyForYouRefreshesUsed", 0),
                freeForYouDailyPicks = obj.optInt("freeForYouDailyPicks", 2),
                autoRenewing = obj.optBoolean("autoRenewing", false)
            )
        }.getOrDefault(BillingUiState())
    }

    companion object {
        private const val TAG = "TuneMindBillingManager"
        private const val PREFS_NAME = "tunemind_billing_entitlement_v1"
        private const val KEY_CACHED_ENTITLEMENT = "entitlement_json"

        const val PRODUCT_ANNUAL = "tunemind_pro_annual"
        const val PRODUCT_MONTHLY = "tunemind_pro_monthly"

        @Volatile
        private var INSTANCE: TuneMindBillingManager? = null

        fun getInstance(context: Context): TuneMindBillingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TuneMindBillingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
