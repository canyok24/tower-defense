package com.toasteralley.emberwatch.data

import android.content.Context
import android.util.Log
import com.toasteralley.emberwatch.core.ActiveResearch
import com.toasteralley.emberwatch.core.ResearchProject
import com.toasteralley.emberwatch.core.SaveData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single persistence point for game progress: one JSON blob in SharedPreferences
 * with a "version" field for future migrations. Store entitlements are kept
 * separately (billing.EntitlementStore) and are never touched by this class.
 */
class SaveStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("emberwatch_save", Context.MODE_PRIVATE)

    fun load(): SaveData {
        val raw = prefs.getString(KEY, null) ?: return SaveData()
        return try { fromJson(JSONObject(raw)) } catch (e: Exception) {
            Log.e("SaveStore", "Corrupt save, keeping backup and starting fresh", e)
            prefs.edit().putString("$KEY.corrupt", raw).apply()
            SaveData()
        }
    }

    fun save(d: SaveData) { prefs.edit().putString(KEY, toJson(d).toString()).apply() }

    companion object {
        private const val KEY = "save"

        fun toJson(d: SaveData) = JSONObject().apply {
            put("version", SaveData.CURRENT_VERSION)
            put("coins", d.coins); put("diamonds", d.diamonds); put("cores", d.cores)
            put("bestWave", d.bestWave); put("totalRuns", d.totalRuns)
            put("unlockedTier", d.unlockedTier); put("selectedTier", d.selectedTier)
            put("milestoneRewardedWave", d.milestoneRewardedWave)
            put("workshop", JSONObject(d.workshop as Map<*, *>))
            put("perks", JSONObject(d.perks as Map<*, *>))
            put("ultimates", JSONObject(d.ultimates as Map<*, *>))
            put("research", JSONObject(d.research as Map<*, *>))
            put("enhancement", d.enhancement)
            put("activeResearch", JSONArray().apply {
                d.activeResearch.forEach {
                    put(JSONObject().put("p", it.project.name).put("lvl", it.targetLevel).put("start", it.startMs).put("end", it.endMs))
                }
            })
            put("boostUntilMs", d.boostUntilMs); put("runsSinceInterstitial", d.runsSinceInterstitial)
            put("soundOn", d.soundOn)
            put("debugGodMode", d.debugGodMode); put("debugBypassGates", d.debugBypassGates)
            put("debugStartWave", d.debugStartWave)
        }

        private fun JSONObject.intMap(key: String, into: MutableMap<String, Int>) {
            val o = optJSONObject(key) ?: return
            o.keys().forEach { into[it] = o.optInt(it) }
        }

        fun fromJson(j: JSONObject): SaveData = SaveData().apply {
            version = j.optInt("version", 1)
            // Migrations by version go here (none yet; v1 is the first format).
            coins = j.optDouble("coins", 0.0).let { if (it.isNaN()) 0.0 else it }
            diamonds = j.optLong("diamonds"); cores = j.optLong("cores")
            bestWave = j.optInt("bestWave"); totalRuns = j.optInt("totalRuns")
            unlockedTier = j.optInt("unlockedTier", 1); selectedTier = j.optInt("selectedTier", 1)
            milestoneRewardedWave = j.optInt("milestoneRewardedWave")
            j.intMap("workshop", workshop); j.intMap("perks", perks)
            j.intMap("ultimates", ultimates); j.intMap("research", research)
            enhancement = j.optInt("enhancement")
            j.optJSONArray("activeResearch")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = runCatching { ResearchProject.valueOf(o.getString("p")) }.getOrNull() ?: continue
                    activeResearch += ActiveResearch(p, o.getInt("lvl"), o.getLong("start"), o.getLong("end"))
                }
            }
            boostUntilMs = j.optLong("boostUntilMs"); runsSinceInterstitial = j.optInt("runsSinceInterstitial")
            soundOn = j.optBoolean("soundOn", true)
            debugGodMode = j.optBoolean("debugGodMode"); debugBypassGates = j.optBoolean("debugBypassGates")
            debugStartWave = j.optInt("debugStartWave", 1)
        }
    }
}
