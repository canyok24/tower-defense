package com.toasteralley.emberwatch.core

import kotlin.math.floor
import kotlin.math.pow

/** Abstraction over System.currentTimeMillis so timers are testable. */
fun interface Clock { fun now(): Long }
object SystemClock : Clock { override fun now() = System.currentTimeMillis() }

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/**
 * Research Bay projects. Costs are Coins + Research Cores; durations are real
 * wall-clock time. [gateWave] is the best wave needed before a project appears.
 */
enum class ResearchProject(
    val label: String,
    val description: String,
    val maxLevel: Int,
    val gateWave: Int,
) {
    LIGHT_SPEED_SHOTS("Light Speed Shots",
        "Shots hit instantly (hitscan beam). Projectile Velocity research converts into pure fire rate.", 1, 10),
    STARTING_CASH("Starting Cash", "+30 opening run cash per level", 25, 0),
    LAB_DAMAGE("Ember Rounds", "+6% damage per level", 40, 3),
    LAB_HEALTH("Hull Integrity Research", "+6% hull integrity (max health) per level", 40, 3),
    LAB_ATTACK_SPEED("Rapid Breech", "+3% fire rate per level", 30, 5),
    PROJECTILE_VELOCITY("Projectile Velocity", "+12% shot speed per level (after Light Speed: +3% fire rate per level)", 10, 4),
    COIN_BONUS("Coin Ledger", "+4% coins per level (multiplies all coin income)", 30, 8),
    CORE_YIELD("Core Siphon", "+10% Research Cores per level", 20, 12),
    BAY_EXPANSION("Bay Expansion", "+1 concurrent research slot", 2, 20);

    fun coinCost(level: Int): Double = when (this) {
        LIGHT_SPEED_SHOTS -> 3_000_000.0
        BAY_EXPANSION -> 250_000.0 * 8.0.pow(level)
        else -> floor(baseCoin() * 1.55.pow(level))
    }

    fun coreCost(level: Int): Long = when (this) {
        LIGHT_SPEED_SHOTS -> 50
        BAY_EXPANSION -> 40L + 60L * level
        else -> (2 + 2 * level).toLong()
    }

    fun durationMs(level: Int): Long = when (this) {
        LIGHT_SPEED_SHOTS -> 20 * HOUR
        BAY_EXPANSION -> (12 + 12 * level) * HOUR
        else -> (baseMinutes() * 1.32.pow(level) * MIN).toLong().coerceAtMost(72 * HOUR)
    }

    private fun baseCoin() = when (this) {
        STARTING_CASH -> 400.0; LAB_DAMAGE -> 600.0; LAB_HEALTH -> 600.0
        LAB_ATTACK_SPEED -> 900.0; PROJECTILE_VELOCITY -> 800.0; COIN_BONUS -> 1500.0
        CORE_YIELD -> 3000.0; else -> 1000.0
    }

    private fun baseMinutes() = when (this) {
        STARTING_CASH -> 5.0; LAB_DAMAGE -> 10.0; LAB_HEALTH -> 10.0; LAB_ATTACK_SPEED -> 15.0
        PROJECTILE_VELOCITY -> 12.0; COIN_BONUS -> 20.0; CORE_YIELD -> 30.0; else -> 10.0
    }

    /** Multiplicative effect (or additive amount for STARTING_CASH / slots). */
    fun effect(level: Int): Double = when (this) {
        LIGHT_SPEED_SHOTS -> if (level > 0) 1.0 else 0.0
        STARTING_CASH -> 30.0 * level
        LAB_DAMAGE, LAB_HEALTH -> 1 + 0.06 * level
        LAB_ATTACK_SPEED -> 1 + 0.03 * level
        PROJECTILE_VELOCITY -> 1 + 0.12 * level
        COIN_BONUS -> 1 + 0.04 * level
        CORE_YIELD -> 1 + 0.10 * level
        BAY_EXPANSION -> level.toDouble()
    }
}

/** Research Bay logic over a [SaveData]. Pure Kotlin; persistence is the caller's job. */
class ResearchBay(private val save: SaveData, private val clock: Clock = SystemClock) {
    companion object { const val BASE_SLOTS = 2 }

    val slots: Int get() = BASE_SLOTS + save.level(ResearchProject.BAY_EXPANSION)

    fun isActive(p: ResearchProject) = save.activeResearch.any { it.project == p }

    fun gateOpen(p: ResearchProject, bypass: Boolean) = bypass || save.bestWave >= p.gateWave

    sealed interface StartResult {
        data object Ok : StartResult
        data class Blocked(val reason: String) : StartResult
    }

    fun check(p: ResearchProject, bypass: Boolean = false): StartResult {
        val lvl = save.level(p)
        return when {
            lvl >= p.maxLevel -> StartResult.Blocked("Maxed")
            isActive(p) -> StartResult.Blocked("In progress")
            !gateOpen(p, bypass) -> StartResult.Blocked("Reach wave ${p.gateWave}")
            save.activeResearch.size >= slots -> StartResult.Blocked("All slots busy")
            save.coins < p.coinCost(lvl) -> StartResult.Blocked("Need coins")
            save.cores < p.coreCost(lvl) -> StartResult.Blocked("Need cores")
            else -> StartResult.Ok
        }
    }

    fun start(p: ResearchProject, bypass: Boolean = false): StartResult {
        val r = check(p, bypass)
        if (r != StartResult.Ok) return r
        val lvl = save.level(p)
        save.coins -= p.coinCost(lvl)
        save.cores -= p.coreCost(lvl)
        val now = clock.now()
        save.activeResearch += ActiveResearch(p, lvl + 1, now, now + p.durationMs(lvl))
        return r
    }

    fun remainingMs(a: ActiveResearch) = (a.endMs - clock.now()).coerceAtLeast(0)

    /** Completes every finished timer. Returns the projects completed. */
    fun tick(): List<ResearchProject> {
        val now = clock.now()
        val done = save.activeResearch.filter { it.endMs <= now }
        done.forEach { finish(it) }
        return done.map { it.project }
    }

    /** Management override: finish all running research immediately. */
    fun completeAllNow(): List<ResearchProject> {
        val all = save.activeResearch.toList()
        all.forEach { finish(it) }
        return all.map { it.project }
    }

    private fun finish(a: ActiveResearch) {
        save.activeResearch.remove(a)
        save.research[a.project.name] = maxOf(save.level(a.project), a.targetLevel)
    }
}
