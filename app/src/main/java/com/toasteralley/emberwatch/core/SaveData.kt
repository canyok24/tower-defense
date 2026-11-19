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
    var bounty: Double = 0.0
    var diamonds: Long = 0
    var cores: Long = 0
    var bestWave: Int = 0
    var totalRuns: Int = 0
    /** Stage chosen on the main menu (1..StageDefinitions.STAGE_COUNT = 5). */
    var selectedStage: Int = 1
    /** Best waves CLEARED per stage (key = stage number). */
    val stageBestWave: MutableMap<Int, Int> = mutableMapOf()
    /** Number of milestones already paid per stage (they are paid in order). */
    val stageMilestonesClaimed: MutableMap<Int, Int> = mutableMapOf()
    /** v1 -> v2 migration: stages 1..N stay unlocked for players who had unlocked old tiers. */
    var legacyUnlockedStage: Int = 1
    val workshop: MutableMap<String, Int> = mutableMapOf()
    val perks: MutableMap<String, Int> = mutableMapOf()
    val ultimates: MutableMap<String, Int> = mutableMapOf()
    var enhancement: Int = 0
    val research: MutableMap<String, Int> = mutableMapOf()
    val activeResearch: MutableList<ActiveResearch> = mutableListOf()
    /** Research tab (Mastery) levels and running timers. */
    val masteries: MutableMap<String, Int> = mutableMapOf()
    val activeMastery: MutableList<ActiveMastery> = mutableListOf()
    /** Rewarded-ad 1.5x bounty boost runs until this wall-clock time. */
    var boostUntilMs: Long = 0
    var runsSinceInterstitial: Int = 0
    var soundOn: Boolean = true

    // Management override (honoured only when BuildConfig.DEBUG — see DebugGate).
    var debugGodMode: Boolean = false
    var debugBypassGates: Boolean = false
    var debugStartWave: Int = 1
    var debugResearchTabUnlocked: Boolean = false

    fun level(u: Upgrade) = workshop[u.name] ?: 0
    fun level(p: DiamondPerk) = perks[p.name] ?: 0
    fun level(u: UltimateWeapon) = ultimates[u.name] ?: 0
    fun level(r: ResearchProject) = research[r.name] ?: 0
    fun level(m: Mastery) = masteries[m.name] ?: 0

    companion object {
        /**
         * v1: 10 difficulty tiers. v2: 24 stages with per-stage best wave + milestone rewards.
         * v3: Workshop split into ASSAULT / SHIELD / LEDGER (old six stats renamed).
         */
        const val CURRENT_VERSION = 5  // v4: Shield renames; v5: coins -> Bounty, Ledger Bounty -> Bounty Gain
    }
}
