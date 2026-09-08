package dev.cameradoctor.baseline

import android.content.Context
import android.os.Build
import dev.cameradoctor.diagnosis.BaselineValue
import org.json.JSONObject

/**
 * Device baseline per endpoint (docs/PRODUCT-v0.2.md 5.3). Keyed by Build.FINGERPRINT + endpoint key + conditions.
 * Created by the first qualifying run, never updated automatically, invalidated when the fingerprint changes.
 */
class BaselineStore(context: Context) {
    private val prefs = context.getSharedPreferences("baseline", Context.MODE_PRIVATE)

    data class Entry(val runId: String, val fingerprint: String, val values: Map<String, BaselineValue>)

    fun key(endpointKey: String, conditions: String) = "${Build.FINGERPRINT}|$endpointKey|$conditions"

    fun get(endpointKey: String, conditions: String): Entry? {
        val raw = prefs.getString(key(endpointKey, conditions), null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (o.getString("fingerprint") != Build.FINGERPRINT) return null
            val v = o.getJSONObject("values")
            Entry(o.getString("run_id"), o.getString("fingerprint"), v.keys().asSequence().associateWith { id ->
                val m = v.getJSONObject(id)
                BaselineValue(m.getDouble("value"), if (m.has("p95") && !m.isNull("p95")) m.getDouble("p95") else null)
            })
        } catch (_: Exception) { null }
    }

    fun put(endpointKey: String, conditions: String, runId: String, values: Map<String, BaselineValue>) {
        val v = JSONObject()
        values.forEach { (id, b) -> v.put(id, JSONObject().put("value", b.value).put("p95", b.p95 ?: JSONObject.NULL)) }
        val o = JSONObject().put("run_id", runId).put("fingerprint", Build.FINGERPRINT).put("values", v)
        prefs.edit().putString(key(endpointKey, conditions), o.toString()).apply()
    }

    fun clear() { prefs.edit().clear().apply() }
}
