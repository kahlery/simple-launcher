package com.example.simplelauncher

import android.content.Context
import android.location.Geocoder
import android.location.Location
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

@Immutable
data class Weather(
    val temp: Int,
    val high: Int,
    val low: Int,
    val code: Int,
    val isDay: Boolean,
    val wind: Int,
    /** Günün en yüksek UV indeksi. */
    val uv: Int,
    val place: String?,
    val fetchedAt: Long,
    /** Önümüzdeki saatler (hava durumu satırına dokununca gösterilir); eski önbellekte boş. */
    val hourly: List<HourForecast> = emptyList(),
)

/** Bir saatlik tahmin; [time] saatin başlangıcı (epoch ms). */
@Immutable
data class HourForecast(
    val time: Long,
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    /** Yağış olasılığı (%). */
    val rain: Int,
    val wind: Int,
    val uv: Int,
)

enum class WeatherStatus { Loading, Ok, Failed, NeedsPermission }

/**
 * Verilen konum için Open-Meteo'dan (API anahtarı gerekmez) güncel hava durumunu çeker.
 * Son sonuç saklanır ki ana ekran açılır açılmaz gösterilebilsin.
 */
class WeatherRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun cached(): Weather? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Weather(
                temp = o.getInt("temp"),
                high = o.getInt("high"),
                low = o.getInt("low"),
                code = o.getInt("code"),
                isDay = o.getBoolean("isDay"),
                wind = o.getInt("wind"),
                uv = o.optInt("uv"),
                place = o.optString("place").ifBlank { null },
                fetchedAt = o.getLong("fetchedAt"),
                hourly = o.optJSONArray("hourly")?.let { arr ->
                    List(arr.length()) {
                        val h = arr.getJSONObject(it)
                        HourForecast(
                            h.getLong("t"), h.getInt("temp"), h.getInt("code"), h.getBoolean("day"),
                            h.getInt("rain"), h.getInt("wind"), h.getInt("uv"),
                        )
                    }
                }.orEmpty(),
            )
        }.getOrNull()
    }

    suspend fun fetch(loc: Location): Weather = withContext(Dispatchers.IO) {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,weather_code,wind_speed_10m,is_day" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m,is_day,uv_index" +
                    "&daily=temperature_2m_max,temperature_2m_min,uv_index_max" +
                    "&timezone=auto&timeformat=unixtime&forecast_days=2",
                loc.latitude,
                loc.longitude,
            ),
        )
        val conn = url.openConnection() as HttpURLConnection
        val body = try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val weather = Weather(
            temp = current.getDouble("temperature_2m").roundToInt(),
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            wind = current.getDouble("wind_speed_10m").roundToInt(),
            uv = daily.optJSONArray("uv_index_max")?.optDouble(0, 0.0)?.roundToInt() ?: 0,
            place = placeName(loc),
            fetchedAt = System.currentTimeMillis(),
            hourly = json.optJSONObject("hourly")?.let(::parseHourly).orEmpty(),
        )
        save(weather)
        weather
    }

    /** İçinde bulunulan saatten başlayarak 24 saat. */
    private fun parseHourly(h: JSONObject): List<HourForecast> {
        val times = h.getJSONArray("time")
        fun num(name: String, i: Int) = h.optJSONArray(name)?.optDouble(i, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val hourStart = System.currentTimeMillis() / HOUR_MS * HOUR_MS
        return List(times.length()) { i ->
            HourForecast(
                time = times.getLong(i) * 1000,
                temp = num("temperature_2m", i).roundToInt(),
                code = num("weather_code", i).toInt(),
                isDay = num("is_day", i) >= 1,
                rain = num("precipitation_probability", i).roundToInt(),
                wind = num("wind_speed_10m", i).roundToInt(),
                uv = num("uv_index", i).roundToInt(),
            )
        }.filter { it.time >= hourStart }.take(24)
    }

    private fun save(w: Weather) {
        val o = JSONObject()
            .put("temp", w.temp)
            .put("high", w.high)
            .put("low", w.low)
            .put("code", w.code)
            .put("isDay", w.isDay)
            .put("wind", w.wind)
            .put("uv", w.uv)
            .put("place", w.place.orEmpty())
            .put("fetchedAt", w.fetchedAt)
            .put(
                "hourly",
                JSONArray(
                    w.hourly.map {
                        JSONObject().put("t", it.time).put("temp", it.temp).put("code", it.code).put("day", it.isDay)
                            .put("rain", it.rain).put("wind", it.wind).put("uv", it.uv)
                    },
                ),
            )
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    @Suppress("DEPRECATION")
    private fun placeName(loc: Location): String? = runCatching {
        val address = Geocoder(context, Locale.ENGLISH)
            .getFromLocation(loc.latitude, loc.longitude, 1)
            ?.firstOrNull()
        address?.locality ?: address?.subAdminArea ?: address?.adminArea
    }.getOrNull()

    private companion object {
        const val KEY = "last"
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
