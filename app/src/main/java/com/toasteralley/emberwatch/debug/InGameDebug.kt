package com.toasteralley.emberwatch.debug

/**
 * Hooks the management-override menu uses inside a run. The menu
 * implementation (DebugTools) only exists in the debug source set; release
 * builds get a stub whose AVAILABLE is false and whose functions do nothing.
 */
interface InGameDebug {
    fun skipWaves(n: Int)
    fun jumpToWave(n: Int)
    fun spawnBoss()
    fun godMode(): Boolean
    fun setGodMode(on: Boolean)
    fun addCash(amount: Double)
    fun heal()
    fun resume()
}
