package com.example.simplelauncher

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Performans tablosundaki dönemler (ay). */
val PERF_PERIODS = listOf(1, 3, 6, 12, 24, 36)

/**
 * Bir satır: [values] her dönem için yüzde değişim (veri yoksa null), [price] güncel fiyat.
 * [inflation] satırlarında değer, o dönemdeki birikimli fiyat artışıdır (12 ay = yıllık enflasyon);
 * fiyat yerine verinin ait olduğu ay [asOf] gösterilir.
 */
@Immutable
data class PerfRow(
    val label: String,
    val values: List<Double?>,
    val inflation: Boolean,
    val price: Double? = null,
    val asOf: YearMonth? = null,
)

@Immutable
data class MarketData(val rows: List<PerfRow>, val fetchedAt: Long)

/** Satırın verisi nereden gelir. */
private sealed interface Source {
    /** Yahoo Finance günlük kapanışları. */
    data class Yahoo(val symbol: String) : Source

    /** İki Yahoo serisinin oranı (ör. altın USD / EURUSD = altın EUR). */
    data class Ratio(val numerator: String, val denominator: String) : Source

    /** Eurostat HICP (aylık endeks). */
    data class Eurostat(val geo: String) : Source

    /** FRED serisi (aylık endeks, CSV). */
    data class Fred(val series: String) : Source
}

private data class Item(val label: String, val source: Source)

/** Tabloda gösterilenler, bu sırayla (ETF'ler en üstte). */
private val ITEMS = listOf(
    Item("CNDX", Source.Yahoo("CNDX.L")),
    Item("EIMI", Source.Yahoo("EIMI.L")),
    Item("ASELS", Source.Yahoo("ASELS.IS")),
    Item("USIRYY", Source.Fred("CPIAUCNS")),
    Item("DEIRYY", Source.Eurostat("DE")),
    Item("TRIRYY", Source.Eurostat("TR")),
    Item("XAUEUR", Source.Ratio("GC=F", "EURUSD=X")),
    Item("USDTRY", Source.Yahoo("USDTRY=X")),
    Item("EURTRY", Source.Yahoo("EURTRY=X")),
)

/** Tarihe göre sıralı fiyat/endeks serisi; [latest] en son değer. */
private class Series(val points: List<Pair<LocalDate, Double>>, val latest: Double, val latestDate: LocalDate) {
    /** [date] günü ya da öncesindeki son değer. */
    fun at(date: LocalDate): Double? = points.lastOrNull { !it.first.isAfter(date) }?.second
}

/**
 * Piyasa ve enflasyon verilerini çekip 1–36 aylık performansları hesaplar. Anahtar gerektirmeyen
 * kaynaklar: Yahoo Finance (fiyatlar), Eurostat (DE/TR HICP), FRED (ABD TÜFE).
 */
class MarketRepository(context: Context) {

    private val prefs = context.getSharedPreferences("markets", Context.MODE_PRIVATE)

    fun cached(): MarketData? {
        val raw = prefs.getString(KEY_DATA, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val rows = json.getJSONArray("rows")
            MarketData(
                List(rows.length()) { i ->
                    val r = rows.getJSONObject(i)
                    val vals = r.getJSONArray("values")
                    PerfRow(
                        r.getString("label"),
                        List(vals.length()) { j -> if (vals.isNull(j)) null else vals.getDouble(j) },
                        r.getBoolean("inflation"),
                        r.optDouble("price").takeIf { !it.isNaN() },
                        r.optString("asOf").takeIf { it.isNotEmpty() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
                    )
                },
                json.getLong("fetchedAt"),
            ).let { data ->
                // Sıra değişmişse önbellekteki veri de yeni sırayla gösterilsin.
                val order = ITEMS.map { it.label }
                data.copy(rows = data.rows.sortedBy { order.indexOf(it.label).let { i -> if (i < 0) Int.MAX_VALUE else i } })
            }
        }.getOrNull()
    }

    /**
     * Tüm satırları paralel çeker. Bir kaynak çökerse o satır önbellekteki hâliyle kalır;
     * hiçbiri gelmezse hata fırlatır.
     */
    suspend fun fetch(): MarketData = withContext(Dispatchers.IO) {
        val old = cached()?.rows?.associateBy { it.label }.orEmpty()
        val yahoo = HashMap<String, Series?>()
        val symbols = ITEMS.flatMap {
            when (val s = it.source) {
                is Source.Yahoo -> listOf(s.symbol)
                is Source.Ratio -> listOf(s.numerator, s.denominator)
                else -> emptyList()
            }
        }.distinct()
        val fresh = coroutineScope {
            val loaded = symbols.map { sym -> async { sym to runCatching { yahooSeries(sym) }.getOrNull() } }.awaitAll()
            yahoo.putAll(loaded)
            ITEMS.map { item -> async { item to runCatching { compute(item, yahoo) }.getOrNull() } }.awaitAll()
        }
        if (fresh.all { it.second == null }) error("piyasa verisi alınamadı")
        val rows = fresh.map { (item, row) ->
            row ?: old[item.label] ?: PerfRow(item.label, PERF_PERIODS.map { null }, item.source.isInflation)
        }
        val data = MarketData(rows, System.currentTimeMillis())
        prefs.edit().putString(KEY_DATA, toJson(data).toString()).apply()
        data
    }

    private fun compute(item: Item, yahoo: Map<String, Series?>): PerfRow? {
        val source = item.source
        var price: Double? = null
        var asOf: YearMonth? = null
        val values: List<Double?> = when (source) {
            is Source.Yahoo -> {
                val s = yahoo[source.symbol] ?: return null
                price = s.latest
                PERF_PERIODS.map { m -> s.at(s.latestDate.minusMonths(m.toLong()))?.let { pct(s.latest, it) } }
            }
            is Source.Ratio -> {
                val num = yahoo[source.numerator] ?: return null
                val den = yahoo[source.denominator] ?: return null
                val now = num.latest / den.latest
                price = now
                val today = maxOf(num.latestDate, den.latestDate)
                PERF_PERIODS.map { m ->
                    val d = today.minusMonths(m.toLong())
                    val n = num.at(d)
                    val e = den.at(d)
                    if (n != null && e != null) pct(now, n / e) else null
                }
            }
            is Source.Eurostat -> eurostat(source.geo).also { asOf = it.keys.maxOrNull() }.let(::monthly)
            is Source.Fred -> fred(source.series).also { asOf = it.keys.maxOrNull() }.let(::monthly)
        }
        if (values.all { it == null }) return null
        return PerfRow(item.label, values, source.isInflation, price, asOf)
    }

    /** Aylık endekste son ayı N ay öncesiyle karşılaştırır (eksik ay varsa daha önceki ilk ay). */
    private fun monthly(index: Map<YearMonth, Double>): List<Double?> {
        if (index.isEmpty()) return PERF_PERIODS.map { null }
        val last = index.keys.max()
        val now = index.getValue(last)
        return PERF_PERIODS.map { m ->
            val target = last.minusMonths(m.toLong())
            index.entries.filter { it.key <= target }.maxByOrNull { it.key }
                ?.takeIf { it.key >= target.minusMonths(2) }
                ?.let { pct(now, it.value) }
        }
    }

    private fun yahooSeries(symbol: String): Series {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            java.net.URLEncoder.encode(symbol, "UTF-8") + "?range=5y&interval=1d"
        // Yahoo, Android'in varsayılan kimliğine "Too Many Requests" döner; tarayıcı gibi görünmek gerekir.
        val result = JSONObject(get(url, userAgent = "Mozilla/5.0")).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val stamps = result.getJSONArray("timestamp")
        val closes = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
        val points = ArrayList<Pair<LocalDate, Double>>(stamps.length())
        for (i in 0 until stamps.length()) {
            if (closes.isNull(i)) continue
            val date = Instant.ofEpochSecond(stamps.getLong(i)).atOffset(ZoneOffset.UTC).toLocalDate()
            points += date to closes.getDouble(i)
        }
        val meta = result.getJSONObject("meta")
        val latest = meta.optDouble("regularMarketPrice").takeIf { !it.isNaN() } ?: points.last().second
        val latestDate = meta.optLong("regularMarketTime").takeIf { it > 0 }
            ?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toLocalDate() }
            ?: points.last().first
        return Series(points, latest, latestDate)
    }

    private fun eurostat(geo: String): Map<YearMonth, Double> {
        val since = YearMonth.now().minusMonths(42)
        val url = "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/prc_hicp_minr" +
            "?format=JSON&geo=$geo&coicop18=TOTAL&unit=I15&sinceTimePeriod=$since"
        val json = JSONObject(get(url))
        val times = json.getJSONObject("dimension").getJSONObject("time").getJSONObject("category").getJSONObject("index")
        val byPos = HashMap<Int, YearMonth>()
        for (key in times.keys()) byPos[times.getInt(key)] = YearMonth.parse(key)
        val values = json.getJSONObject("value")
        val result = HashMap<YearMonth, Double>()
        for (key in values.keys()) {
            val month = byPos[key.toInt()] ?: continue
            result[month] = values.getDouble(key)
        }
        return result
    }

    private fun fred(series: String): Map<YearMonth, Double> {
        val since = YearMonth.now().minusMonths(42).atDay(1)
        val csv = get("https://fred.stlouisfed.org/graph/fredgraph.csv?id=$series&cosd=$since")
        val result = HashMap<YearMonth, Double>()
        for (line in csv.lineSequence().drop(1)) {
            val parts = line.split(",")
            if (parts.size < 2) continue
            // Yayınlanmayan aylar boş gelir (ör. Ekim 2025); atlanır.
            val value = parts[1].trim().toDoubleOrNull() ?: continue
            result[YearMonth.from(LocalDate.parse(parts[0].trim()))] = value
        }
        return result
    }

    /** FRED (Akamai) ise "Mozilla/5.0" kimliğini engelliyor; varsayılan kimlik orada çalışır. */
    private fun get(url: String, userAgent: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun toJson(data: MarketData) = JSONObject()
        .put("fetchedAt", data.fetchedAt)
        .put(
            "rows",
            JSONArray(
                data.rows.map { r ->
                    JSONObject()
                        .put("label", r.label)
                        .put("inflation", r.inflation)
                        .put("values", JSONArray(r.values.map { it ?: JSONObject.NULL }))
                        .put("price", r.price ?: JSONObject.NULL)
                        .put("asOf", r.asOf?.toString() ?: JSONObject.NULL)
                },
            ),
        )

    private companion object {
        const val KEY_DATA = "data"
    }
}

private val Source.isInflation: Boolean get() = this is Source.Eurostat || this is Source.Fred

private fun pct(now: Double, then: Double): Double? = if (then == 0.0) null else (now / then - 1) * 100
