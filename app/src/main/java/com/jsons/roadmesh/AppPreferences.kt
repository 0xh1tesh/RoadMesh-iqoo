package com.jsons.roadmesh

import android.content.Context
import com.jsons.roadmesh.core.mesh.*
import org.json.JSONObject
import java.security.SecureRandom

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("roadmesh_base", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    val nodeId = NodeId(prefs.getLong("node", 0).takeIf { it != 0L } ?: random.nextLong().let { if (it == 0L) 4L else it }.also {
        prefs.edit().putLong("node", it).apply()
    })
    val alias = prefs.getInt("alias", 0).takeIf { it != 0 } ?: (1 + random.nextInt(0xfffffe)).also { prefs.edit().putInt("alias", it).apply() }
    var role: Role
        get() = runCatching { Role.valueOf(prefs.getString("role", null) ?: "OBSERVER") }.getOrDefault(Role.OBSERVER)
        set(value) { prefs.edit().putString("role", value.name).apply() }
    var vehicle: VehicleType
        get() = VehicleType.from(prefs.getInt("vehicle", VehicleType.CAR.code))
        set(value) { prefs.edit().putInt("vehicle", value.code).apply() }
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) { prefs.edit().putBoolean("onboarded", value).apply() }
    val profile: MeshProfile get() = runCatching { parseProfile(prefs.getString("profile", null) ?: "{}") }.getOrElse { MeshProfile() }
    fun saveProfile(json: String): MeshProfile = parseProfile(json).also {
        prefs.edit().putString("profile", profileJson(it).toString(2)).apply()
    }
    fun setMode(mode: Mode) = saveProfile(profileJson(profile.copy(mode = mode, initialTtl = if (mode == Mode.STAGE) 1 else 2)).toString())
}

fun profileJson(p: MeshProfile) = JSONObject().apply {
    put("schema", 1); put("mode", p.mode.name); put("initialTtl", p.initialTtl)
    put("maxAgeMs", p.maxAgeMs); put("futureToleranceMs", p.futureToleranceMs)
    put("dedupCapacity", p.dedupCapacity); put("dedupRetentionMs", p.dedupRetentionMs)
    put("neighborCapacity", p.neighborCapacity); put("neighborRetentionMs", p.neighborRetentionMs)
    put("pendingCapacity", p.pendingCapacity); put("maxRelayDelayMs", p.maxRelayDelayMs); put("jitterMs", p.jitterMs)
    put("suppressionCopies", p.suppressionCopies); put("rangeM", p.rangeM); put("relevanceM", p.relevanceM)
    put("relayPerSecond", p.relayPerSecond); put("originPerSecond", p.originPerSecond)
}
fun parseProfile(text: String): MeshProfile {
    val j = JSONObject(text)
    val allowed = profileJson(MeshProfile()).keys().asSequence().toSet()
    j.keys().forEach { require(it in allowed) { "Unknown setting: $it" } }
    if (j.has("schema")) require(j.get("schema") is Number && (j.get("schema") as Number).toDouble() == 1.0) { "schema must be integer 1" }
    if (j.has("mode")) require(j.get("mode") is String) { "mode must be STAGE or ROAD" }
    val mode = Mode.valueOf(j.optString("mode", "STAGE"))
    val d = MeshProfile(mode)
    fun int(key: String, default: Int): Int {
        if (!j.has(key)) return default
        val value = j.get(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "$key must be an integer" }
        return value.toInt()
    }
    fun long(key: String, default: Long) = int(key, default.toInt()).toLong()
    fun double(key: String, default: Double): Double {
        if (!j.has(key)) return default
        val value = j.get(key)
        require(value is Number && value.toDouble().isFinite()) { "$key must be a number" }
        return value.toDouble()
    }
    return d.copy(initialTtl = int("initialTtl", d.initialTtl), maxAgeMs = long("maxAgeMs", d.maxAgeMs),
        futureToleranceMs = long("futureToleranceMs", d.futureToleranceMs), dedupCapacity = int("dedupCapacity", d.dedupCapacity),
        dedupRetentionMs = long("dedupRetentionMs", d.dedupRetentionMs), neighborCapacity = int("neighborCapacity", d.neighborCapacity),
        neighborRetentionMs = long("neighborRetentionMs", d.neighborRetentionMs), pendingCapacity = int("pendingCapacity", d.pendingCapacity),
        maxRelayDelayMs = int("maxRelayDelayMs", d.maxRelayDelayMs), jitterMs = int("jitterMs", d.jitterMs),
        suppressionCopies = int("suppressionCopies", d.suppressionCopies), rangeM = double("rangeM", d.rangeM),
        relevanceM = double("relevanceM", d.relevanceM), relayPerSecond = int("relayPerSecond", d.relayPerSecond),
        originPerSecond = int("originPerSecond", d.originPerSecond)).validate()
}
