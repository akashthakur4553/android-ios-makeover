package com.glass.launcher.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Immutable
data class Weather(
    val city: String,
    val temp: Int,
    val code: Int,
    val high: Int,
    val low: Int,
    val isDay: Boolean,
    val hourly: List<Triple<String, Int, Int>>, // label, code, temp
    val fetchedAt: Long,
)

/** Weather from Open-Meteo (free, no account). Only a rounded (~1 km) location is sent. */
class WeatherRepo(private val ctx: Context, private val scope: CoroutineScope) {
    private val sp = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    var weather by mutableStateOf<Weather?>(null); private set
    var needsPermission by mutableStateOf(false); private set
    private var loading = false

    init {
        sp.getString("last", null)?.let { runCatching { weather = parse(JSONObject(it), sp.getString("city", "") ?: "") } }
    }

    fun refreshIfStale() {
        val w = weather
        if (w == null || System.currentTimeMillis() - w.fetchedAt > 30 * 60_000) refresh()
    }

    fun refresh() {
        if (loading) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needsPermission = true; return
        }
        needsPermission = false
        loading = true
        scope.launch {
            try {
                val loc = withContext(Dispatchers.IO) { location() } ?: return@launch
                val lat = "%.2f".format(Locale.US, loc.latitude)
                val lon = "%.2f".format(Locale.US, loc.longitude)
                val city = withContext(Dispatchers.IO) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                    }.getOrNull() ?: ""
                }
                val json = withContext(Dispatchers.IO) {
                    val u = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto")
                    val c = u.openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
                }
                val o = JSONObject(json)
                o.put("_t", System.currentTimeMillis())
                weather = parse(o, city)
                sp.edit().putString("last", o.toString()).putString("city", city).apply()
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun location(): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun parse(o: JSONObject, city: String): Weather {
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        val hourly = o.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val nowIso = cur.getString("time").take(13) // yyyy-MM-ddTHH
        var start = 0
        for (i in 0 until times.length()) if (times.getString(i).take(13) == nowIso) { start = i; break }
        val hours = (start until minOf(start + 6, times.length())).mapIndexed { idx, i ->
            val h = times.getString(i).substring(11, 13).toInt()
            val label = if (idx == 0) "Now" else when {
                h == 0 -> "12AM"; h < 12 -> "${h}AM"; h == 12 -> "12PM"; else -> "${h - 12}PM"
            }
            Triple(label, codes.getInt(i), Math.round(temps.getDouble(i)).toInt())
        }
        return Weather(
            city = city.ifBlank { "My Location" },
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            code = cur.getInt("weather_code"),
            high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
            isDay = cur.optInt("is_day", 1) == 1,
            hourly = hours,
            fetchedAt = o.optLong("_t", 0L),
        )
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"; 1 -> "Mostly Clear"; 2 -> "Partly Cloudy"; 3 -> "Cloudy"
            45, 48 -> "Foggy"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
            in 80..82 -> "Showers"; 85, 86 -> "Snow Showers"; in 95..99 -> "Thunderstorms"; else -> "—"
        }
    }
}
