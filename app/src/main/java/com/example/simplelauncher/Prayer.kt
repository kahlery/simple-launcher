package com.example.simplelauncher

import android.content.Context
import android.location.Location
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class PrayerTime(
    val name: String,
    val at: Long,
)

/** Bugün ve yarının ezan vakitleri; hangi konum ve gün için çekildiği de saklanır. */
@Immutable
data class PrayerSchedule(
    val lat: Double,
    val lon: Double,
    val day: String,
    val times: List<PrayerTime>,
) {
    fun next(now: Long): PrayerTime? = times.firstOrNull { it.at > now }
}

/**
 * Ezan vakitlerini Aladhan'dan (API anahtarı gerekmez) Diyanet yöntemiyle çeker.
 * Yüksek enlemlerde yazın sabah/yatsı formülle hesaplanamadığı için yerel hesap yerine servis kullanılır.
 */
class PrayerRepository(context: Context) {

    private val prefs = context.getSharedPreferences("prayer", Context.MODE_PRIVATE)

    fun cached(): PrayerSchedule? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("times")
            PrayerSchedule(
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                day = o.getString("day"),
                times = List(arr.length()) {
                    val t = arr.getJSONObject(it)
                    PrayerTime(t.getString("name"), t.getLong("at"))
                },
            )
        }.getOrNull()
    }

    /** Bugün için ve aşağı yukarı aynı yerde çekilmişse yeniden çekmeye gerek yok. */
    fun isFresh(schedule: PrayerSchedule, loc: Location): Boolean {
        if (schedule.day != LocalDate.now().toString()) return false
        val distance = FloatArray(1)
        Location.distanceBetween(schedule.lat, schedule.lon, loc.latitude, loc.longitude, distance)
        return distance[0] < MAX_DISTANCE_M
    }

    suspend fun fetch(loc: Location): PrayerSchedule = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val times = listOf(today, today.plusDays(1)).flatMap { fetchDay(it, loc) }.sortedBy { it.at }
        val schedule = PrayerSchedule(loc.latitude, loc.longitude, today.toString(), times)
        save(schedule)
        schedule
    }

    private fun fetchDay(date: LocalDate, loc: Location): List<PrayerTime> {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.aladhan.com/v1/timings/%s?latitude=%.4f&longitude=%.4f&method=13&iso8601=true",
                date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")),
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
        val timings = JSONObject(body).getJSONObject("data").getJSONObject("timings")
        return PRAYERS.map { (key, name) ->
            PrayerTime(name, OffsetDateTime.parse(timings.getString(key)).toInstant().toEpochMilli())
        }
    }

    private fun save(s: PrayerSchedule) {
        val arr = JSONArray()
        s.times.forEach { arr.put(JSONObject().put("name", it.name).put("at", it.at)) }
        val o = JSONObject()
            .put("lat", s.lat)
            .put("lon", s.lon)
            .put("day", s.day)
            .put("times", arr)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private companion object {
        const val KEY = "schedule_en"
        const val MAX_DISTANCE_M = 5_000f

        // Güneş doğuşu ezan vakti olmadığı için yok.
        val PRAYERS = listOf(
            "Fajr" to "Fajr",
            "Dhuhr" to "Dhuhr",
            "Asr" to "Asr",
            "Maghrib" to "Maghrib",
            "Isha" to "Isha",
        )
    }
}
