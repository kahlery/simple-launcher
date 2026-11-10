package com.example.simplelauncher

import android.content.Context
import android.location.Geocoder
import android.location.Location
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            )
        }.getOrNull()
    }

    suspend fun fetch(loc: Location): Weather = withContext(Dispatchers.IO) {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,weather_code,wind_speed_10m,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min,uv_index_max&timezone=auto&forecast_days=1",
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
        )
        save(weather)
        weather
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
    }
}
