package com.glass.launcher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Small, Compose-observable settings store. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var showLabels by bool("labels", true)
    var liquidGlass by bool("liquid", true)
    var islandEnabled by bool("island", true)
    var cornerControlCentre by bool("cornerCC", true)
    var islandBanners by bool("banners", true)
    var followSystemWallpaper by bool("followWp", true)
    var haptics by bool("haptics", true)
    var onboarded by bool("onboarded", false)

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        val state = mutableStateOf(sp.getBoolean(key, def))
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            state.value = value; sp.edit().putBoolean(key, value).apply()
        }
    }
}

/** Remembers how often / how recently apps were opened — powers "Siri Suggestions". */
class UsageTracker(ctx: Context) {
    private val sp = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val counts = HashMap<String, Pair<Int, Long>>()
    var version by mutableStateOf(0); private set

    init {
        try {
            val o = JSONObject(sp.getString("data", "{}") ?: "{}")
            o.keys().forEach { k -> val a = o.getJSONArray(k); counts[k] = a.getInt(0) to a.getLong(1) }
        } catch (_: Throwable) {
        }
    }

    fun record(key: String) {
        val (c, _) = counts[key] ?: (0 to 0L)
        counts[key] = (c + 1) to System.currentTimeMillis()
        version++
        val o = JSONObject()
        counts.forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v.first).put(v.second)) }
        sp.edit().putString("data", o.toString()).apply()
    }

    /** Score: launches, decayed by how long ago the app was last used (half-life ~3 days). */
    fun score(key: String): Double {
        val (c, last) = counts[key] ?: return 0.0
        val days = (System.currentTimeMillis() - last) / 86_400_000.0
        return c * Math.pow(0.5, days / 3.0) + if (days < 0.05) 5.0 else 0.0
    }

    fun lastUsed(key: String): Long = counts[key]?.second ?: 0L
}
