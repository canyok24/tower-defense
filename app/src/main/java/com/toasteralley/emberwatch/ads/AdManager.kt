package com.toasteralley.emberwatch.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.toasteralley.emberwatch.BuildConfig

/**
 * AdMob facade. Defaults are Google's official TEST ad unit ids (see
 * app/build.gradle.kts); replace them for production. Banner + interstitial are
 * suppressed when the player owns "no_ads". Rewarded ads stay available (they
 * are opt-in) and grant a 20-minute 1.5x coin boost.
 *
 * NOTE: before serving ads in the EEA/UK you must collect consent with Google's
 * User Messaging Platform (UMP) SDK - see README.
 */
object AdManager {
    @Volatile private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var loadingRewarded = false

    fun initialize(context: Context) {
        if (initialized || !BuildConfig.USE_ADMOB) return
        initialized = true
        Thread { MobileAds.initialize(context.applicationContext) {} }.start()
    }

    fun bindBanner(activity: Activity, container: FrameLayout, noAds: Boolean) {
        if (noAds) {
            for (i in 0 until container.childCount) (container.getChildAt(i) as? AdView)?.destroy()
            container.removeAllViews(); container.visibility = android.view.View.GONE; return
        }
        container.visibility = android.view.View.VISIBLE
        if (container.childCount > 0) return
        val v = if (BuildConfig.USE_ADMOB) AdView(activity).apply {
            setAdSize(AdSize.BANNER); adUnitId = BuildConfig.ADMOB_BANNER_ID
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            loadAd(AdRequest.Builder().build())
        } else TextView(activity).apply {
            text = "Ad placeholder"; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * activity.resources.displayMetrics.density).toInt())
        }
        container.addView(v)
    }

    fun preloadInterstitial(context: Context) {
        if (!BuildConfig.USE_ADMOB || interstitial != null) return
        InterstitialAd.load(context, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(e: LoadAdError) { interstitial = null }
            })
    }

    /** Shows an interstitial if loaded. [onDone] always runs. */
    fun showInterstitial(activity: Activity, noAds: Boolean, onDone: () -> Unit) {
        val ad = interstitial
        if (noAds || ad == null) { onDone(); return }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { onDone(); preloadInterstitial(activity) }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { onDone() }
        }
        ad.show(activity)
    }

    fun preloadRewarded(context: Context) {
        if (!BuildConfig.USE_ADMOB || rewarded != null || loadingRewarded) return
        loadingRewarded = true
        RewardedAd.load(context, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { rewarded = ad; loadingRewarded = false }
                override fun onAdFailedToLoad(e: LoadAdError) { rewarded = null; loadingRewarded = false }
            })
    }

    val rewardedReady get() = rewarded != null || !BuildConfig.USE_ADMOB

    /** Shows a rewarded ad; [onReward] runs only if the user earned the reward. */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) {
        if (!BuildConfig.USE_ADMOB) { onReward(); return } // placeholder mode
        val ad = rewarded ?: run { preloadRewarded(activity); onUnavailable(); return }
        rewarded = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { preloadRewarded(activity) }
        }
        ad.show(activity) { onReward() }
    }
}
