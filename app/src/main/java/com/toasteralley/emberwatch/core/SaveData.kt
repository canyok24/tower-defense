package com.toasteralley.emberwatch.core

/** One running research project. Times are wall-clock epoch millis (survive app close). */
data class ActiveResearch(val project: ResearchProject, val targetLevel: Int, val startMs: Long, val endMs: Long)

/**
 * The whole persistent game state except store entitlements (those live in
 * EntitlementStore and are never wiped). Serialized by data.SaveStore.
 * Maps are keyed by enum name so reordering enums never corrupts saves.
 */
class SaveData {
    var version: Int = CURRENT_VERSION
    var coins: Double = 0.0
    var diamonds: Long = 0
    var cores: Long = 0
    var bestWave: Int = 0
    var totalRuns: Int = 0
    var unlockedTier: Int = 1
    var selectedTier: Int = 1
    /** Highest best-wave milestone (multiple of 10) already paid out in Diamonds. */
    var milestoneRewardedWave: Int = 0
    val workshop: MutableMap<String, Int> = mutableMapOf()
    val perks: MutableMap<String, Int> = mutableMapOf()
    val ultimates: MutableMap<String, Int> = mutableMapOf()
    var enhancement: Int = 0
    val research: MutableMap<String, Int> = mutableMapOf()
    val activeResearch: MutableList<ActiveResearch> = mutableListOf()
    /** Rewarded-ad 1.5x coin boost runs until this wall-clock time. */
    var boostUntilMs: Long = 0
    var runsSinceInterstitial: Int = 0
    var soundOn: Boolean = true

    // Management override (honoured only when BuildConfig.DEBUG — see DebugGate).
    var debugGodMode: Boolean = false
    var debugBypassGates: Boolean = false
    var debugStartWave: Int = 1

    fun level(s: WorkshopStat) = workshop[s.name] ?: 0
    fun level(p: DiamondPerk) = perks[p.name] ?: 0
    fun level(u: UltimateWeapon) = ultimates[u.name] ?: 0
    fun level(r: ResearchProject) = research[r.name] ?: 0

    companion object { const val CURRENT_VERSION = 1 }
}
