package com.toasteralley.towerdefense.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.toasteralley.towerdefense.BuildConfig

/** Something that can produce a banner view. */
interface BannerAdProvider {
    fun createBanner(activity: Activity): View
    fun pause(view: View) {}
    fun resume(view: View) {}
    fun destroy(view: View) {}
}

/** A grey "Ad" bar - used when AdMob is disabled (BuildConfig.USE_ADMOB = false). */
class PlaceholderBannerProvider : BannerAdProvider {
    override fun createBanner(activity: Activity): View = TextView(activity).apply {
        text = "Ad placeholder · buy \"Remove Ads\" in the Store to hide"
        setTextColor(Color.LTGRAY)
        setBackgroundColor(0xFF263238.toInt())
        gravity = Gravity.CENTER
        textSize = 12f
        val h = (50 * activity.resources.displayMetrics.density).toInt()
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }
}

/**
 * Google Mobile Ads banner. The default ad unit is Google's official TEST
 * banner id (ca-app-pub-3940256099942544/6300978111). Replace it (and the
 * app id in the manifest placeholder) with your own ids before release - see
 * README. Never click your own live ads.
 */
class AdMobBannerProvider(private val adUnitId: String) : BannerAdProvider {
    override fun createBanner(activity: Activity): View = AdView(activity).apply {
        setAdSize(AdSize.BANNER)
        adUnitId = this@AdMobBannerProvider.adUnitId
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        )
        loadAd(AdRequest.Builder().build())
    }
    override fun pause(view: View) { (view as? AdView)?.pause() }
    override fun resume(view: View) { (view as? AdView)?.resume() }
    override fun destroy(view: View) { (view as? AdView)?.destroy() }
}

/**
 * Small facade used by activities: shows a banner in a container unless the
 * player owns "remove_ads", in which case the container is hidden entirely.
 */
object AdManager {
    @Volatile private var initialized = false

    private val provider: BannerAdProvider by lazy {
        if (BuildConfig.USE_ADMOB) AdMobBannerProvider(BuildConfig.ADMOB_BANNER_ID) else PlaceholderBannerProvider()
    }

    fun initialize(context: Context) {
        if (initialized || !BuildConfig.USE_ADMOB) return
        initialized = true
        // Initialize off the main thread as recommended by Google.
        Thread { MobileAds.initialize(context.applicationContext) {} }.start()
    }

    /** Attach or remove the banner depending on [removeAds]. Safe to call repeatedly. */
    fun bindBanner(activity: Activity, container: FrameLayout, removeAds: Boolean) {
        if (removeAds) {
            releaseBanner(container)
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        if (container.childCount == 0) container.addView(provider.createBanner(activity))
    }

    fun onPause(container: FrameLayout) { container.getChildAt(0)?.let(provider::pause) }
    fun onResume(container: FrameLayout) { container.getChildAt(0)?.let(provider::resume) }

    fun releaseBanner(container: FrameLayout) {
        for (i in 0 until container.childCount) provider.destroy(container.getChildAt(i))
        container.removeAllViews()
    }
}
