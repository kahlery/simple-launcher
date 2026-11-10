package com.example.simplelauncher

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val bannerText = TextStyle(
    fontFamily = LauncherFont,
    color = Color.White,
    fontSize = 13.sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 4f),
)

/** Ana ekranın en üstündeki ince, satır genişliğinde hava durumu şeridi: yalnızca tek renk ikon ve yazı. */
@Composable
fun WeatherBanner(
    weather: Weather?,
    status: WeatherStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (weather != null) {
            WeatherGlyph(
                code = weather.code,
                isDay = weather.isDay,
                color = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            WrappingParts(
                parts = listOf(
                    "${weather.temp}°",
                    "↑${weather.high}° ↓${weather.low}°",
                    "UV ${weather.uv}",
                    "${weather.wind} km/h",
                ),
                modifier = Modifier.weight(1f),
            )
            weather.place?.let {
                BasicText(
                    text = it,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    style = bannerText,
                )
            }
        } else {
            BasicText(
                text = when (status) {
                    WeatherStatus.NeedsPermission -> "Tap to allow location for weather"
                    WeatherStatus.Failed -> "Couldn't load weather · tap to retry"
                    else -> "Loading weather…"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = bannerText,
            )
        }
    }
}

private const val SEPARATOR = " · "

/**
 * Parçaları " · " ile dizer; sığmazsa yalnızca ayraçlarda alt satıra geçer ve satır sonunda
 * ayraç noktası kalmaz (kırılan ayraç ilk çizimden sonra satır başıyla değiştirilir).
 */
@Composable
private fun WrappingParts(parts: List<String>, modifier: Modifier = Modifier) {
    // Parçaların içindeki boşluklar bölünmez, böylece "8 km/h" gibi bir parça ikiye ayrılmaz.
    val safeParts = parts.map { it.replace(' ', '\u00A0') }
    var breaks by remember(safeParts) { mutableStateOf(emptySet<Int>()) }

    val separatorStarts = IntArray(safeParts.size)
    val text = buildString {
        safeParts.forEachIndexed { i, part ->
            if (i > 0) {
                separatorStarts[i] = length
                append(if (i in breaks) "\n" else SEPARATOR)
            }
            append(part)
        }
    }

    BasicText(
        text = text,
        modifier = modifier,
        style = bannerText,
        onTextLayout = { layout ->
            val found = breaks.toMutableSet()
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                for (i in 1 until safeParts.size) {
                    if (i in breaks) continue
                    val start = separatorStarts[i]
                    if (end > start && end <= start + SEPARATOR.length) found += i
                }
            }
            if (found != breaks) breaks = found
        },
    )
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Hava durumu şeridinin altında: bir sonraki ezan vakti ve ona kalan süre, saniye saniye. */
@Composable
fun PrayerCountdown(
    schedule: PrayerSchedule?,
    needsPermission: Boolean,
    onStale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Saniye gösterilmiyor; dakika başlarında güncellemek yeter (vakitler tam dakikada).
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val next = schedule?.next(now)

    // Elimizdeki vakitler bitti (gün döndü ya da uzun süre yenilenmedi): yenilemeyi iste.
    LaunchedEffect(schedule, next == null) {
        if (schedule != null && next == null) onStale()
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (next != null) {
            val time = Instant.ofEpochMilli(next.at).atZone(ZoneId.systemDefault()).format(clockFormat)
            BasicText(
                text = "${next.name} $time",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = bannerText,
            )
            BasicText(
                text = "in ${countdownText(next.at - now)}",
                maxLines = 1,
                style = bannerText,
            )
        } else {
            BasicText(
                text = if (needsPermission) "Location needed for prayer times" else "Loading prayer times…",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = bannerText,
            )
        }
    }
}

/** 1h 23m ya da bir saatten azsa 23m; dakika yukarı yuvarlanır, vakitte tam 0'a iner. */
private fun countdownText(millis: Long): String {
    val total = (millis + 59_999) / 60_000
    val h = total / 60
    val m = total % 60
    return if (h > 0) String.format(Locale.US, "%dh %02dm", h, m) else "${m}m"
}

/**
 * Ezan vaktinin altında: N26 bakiyesi. Bağlı değilse ne yapılacağını yazar.
 * Dokun → gizle/göster (bağlıysa) ya da bağlan, uzun bas → hemen yenile.
 */
/** Eşik renkleri: koyu arka planda okunur tonlar. */
val BALANCE_RED = Color(0xFFFF5449)
val BALANCE_YELLOW = Color(0xFFFFC94D)
val BALANCE_GREEN = Color(0xFF5BD47A)

fun BalanceLevel.color(): Color = when (this) {
    BalanceLevel.Red -> BALANCE_RED
    BalanceLevel.Yellow -> BALANCE_YELLOW
    BalanceLevel.Green -> BALANCE_GREEN
}

/**
 * Bir bankanın satırı: adı ve tüm hesaplarının bakiyeleri. Her tutar [levelOf] eşiğine göre renklenir
 * (gizliyken renk de gizlenir).
 */
@Composable
fun BankRow(
    label: String,
    balance: BankBalance?,
    status: BankStatus,
    hidden: Boolean,
    levelOf: (Money) -> BalanceLevel?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val connected = status != BankStatus.NotConfigured && status != BankStatus.NotConnected &&
        status != BankStatus.Expired

    Row(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { tap() }, onLongPress = { longPress() })
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (balance != null && connected) {
            Crossfade(targetState = hidden, modifier = Modifier.weight(1f), label = "bankHidden") { isHidden ->
                val text = buildAnnotatedString {
                    append(label)
                    append(' ')
                    balance.amounts.forEachIndexed { i, m ->
                        if (i > 0) append(" · ")
                        val level = if (isHidden) null else levelOf(m)
                        if (level != null) {
                            withStyle(SpanStyle(color = level.color())) { append(money(m)) }
                        } else {
                            append(if (isHidden) hiddenMoney(m.currency) else money(m))
                        }
                    }
                }
                BasicText(
                    text = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = bannerText,
                )
            }
            val updated = Instant.ofEpochMilli(balance.fetchedAt).atZone(ZoneId.systemDefault()).format(clockFormat)
            BasicText(
                text = when (status) {
                    BankStatus.Loading -> "updating…"
                    BankStatus.Failed -> "offline · $updated"
                    else -> updated
                },
                maxLines = 1,
                style = bannerText,
            )
        } else {
            BasicText(
                text = "${label.trimEnd()} · " + when (status) {
                    BankStatus.NotConfigured -> "add Enable Banking keys"
                    BankStatus.NotConnected -> "tap to connect"
                    BankStatus.Expired -> "access expired, tap to reconnect"
                    BankStatus.Failed -> "couldn't connect, tap to retry"
                    else -> "loading…"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = bannerText,
            )
        }
    }
}

fun money(b: Money): String = runCatching {
    NumberFormat.getCurrencyInstance(Locale.US)
        .apply { currency = Currency.getInstance(b.currency) }
        .format(BigDecimal(b.amount))
}.getOrDefault("${b.amount} ${b.currency}")

private fun hiddenMoney(currency: String): String =
    runCatching { Currency.getInstance(currency).getSymbol(Locale.US) }.getOrDefault(currency) + " ••••"

/** Açık gök (WMO 0). */
const val CLEAR_SKY = 0

/**
 * Çizgiyle çizilmiş tek renk hava durumu ikonu; her WMO kodunun kendi ikonu var.
 * Yağış türü işaretle (çisenti kısa çizgi, yağmur uzun çizgi, kar yıldız, kar tanesi nokta,
 * dolu içi boş daire, donma buz yıldızı), şiddeti işaret sayısıyla ayrılır.
 * Sağanaklar (80–86) güneş/ay bulutun arkasından görünerek ayrılır.
 */
@Composable
fun WeatherGlyph(code: Int, isDay: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val thin = Stroke(width = s * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun sky(c: Offset, r: Float) = if (isDay) sun(c, r, color, stroke) else moon(c, r * 1.5f, color, stroke)

        // Yağışlı ikonlarda bulut üstte durur, işaretler altına gelir.
        fun rainCloud() = cloud(0f, s * 0.66f, s, color, stroke)

        // Sağanak: güneş/ay bulutun arkasından görünür.
        fun showerCloud() {
            sky(Offset(s * 0.3f, s * 0.27f), s * 0.11f)
            cloud(s * 0.14f, s * 0.68f, s * 0.86f, color, stroke, erase = true)
        }

        fun drops(xs: List<Float>, len: Float = 0.18f) {
            for (x in xs) line(color, s * x, s * 0.78f, s * (x - 0.06f), s * (0.78f + len), thin)
        }

        fun dashes(xs: List<Float>) {
            xs.forEachIndexed { i, x ->
                val y = if (i % 2 == 0) 0.8f else 0.9f
                line(color, s * x, s * y, s * (x - 0.03f), s * (y + 0.06f), thin)
            }
        }

        fun flakes(xs: List<Float>) {
            xs.forEachIndexed { i, x -> flake(Offset(s * x, s * (if (i % 2 == 0) 0.84f else 0.88f)), s * 0.09f, color, thin) }
        }

        fun ice(x: Float) = flake(Offset(s * x, s * 0.86f), s * 0.08f, color, thin)

        when (code) {
            0 -> if (isDay) sun(Offset(s / 2, s / 2), s * 0.2f, color, stroke)
            else moon(Offset(s / 2, s / 2), s * 0.32f, color, stroke)

            // Çoğunlukla açık: büyük güneş/ay, köşede küçük bir bulut.
            1 -> {
                sky(Offset(s * 0.4f, s * 0.4f), s * 0.16f)
                cloud(s * 0.42f, s * 0.96f, s * 0.58f, color, stroke, erase = true)
            }

            // Parçalı bulutlu: güneş/ay bulutun arkasında.
            2 -> {
                sky(Offset(s * 0.36f, s * 0.36f), s * 0.13f)
                cloud(s * 0.18f, s * 0.9f, s * 0.82f, color, stroke, erase = true)
            }

            // Kapalı: üst üste iki bulut.
            3 -> {
                cloud(s * 0.24f, s * 0.6f, s * 0.76f, color, stroke)
                cloud(0f, s * 0.9f, s * 0.84f, color, stroke, erase = true)
            }

            45 -> {
                line(color, s * 0.15f, s * 0.3f, s * 0.85f, s * 0.3f, stroke)
                line(color, s * 0.25f, s * 0.5f, s * 0.9f, s * 0.5f, stroke)
                line(color, s * 0.1f, s * 0.7f, s * 0.75f, s * 0.7f, stroke)
            }

            // Kırağılı sis: sis çizgileri + buz yıldızı.
            48 -> {
                line(color, s * 0.08f, s * 0.36f, s * 0.6f, s * 0.36f, stroke)
                line(color, s * 0.16f, s * 0.56f, s * 0.92f, s * 0.56f, stroke)
                line(color, s * 0.08f, s * 0.76f, s * 0.7f, s * 0.76f, stroke)
                flake(Offset(s * 0.82f, s * 0.24f), s * 0.11f, color, thin)
            }

            51 -> { rainCloud(); dashes(listOf(0.4f, 0.64f)) }
            53 -> { rainCloud(); dashes(listOf(0.3f, 0.5f, 0.7f)) }
            55 -> { rainCloud(); dashes(listOf(0.22f, 0.38f, 0.54f, 0.7f, 0.86f)) }
            56 -> { rainCloud(); dashes(listOf(0.36f)); ice(0.64f) }
            57 -> { rainCloud(); dashes(listOf(0.28f, 0.48f)); ice(0.72f) }

            61 -> { rainCloud(); drops(listOf(0.42f, 0.66f), len = 0.13f) }
            63 -> { rainCloud(); drops(listOf(0.32f, 0.52f, 0.72f)) }
            65 -> { rainCloud(); drops(listOf(0.24f, 0.4f, 0.56f, 0.72f, 0.88f), len = 0.21f) }
            66 -> { rainCloud(); drops(listOf(0.38f), len = 0.15f); ice(0.66f) }
            67 -> { rainCloud(); drops(listOf(0.28f, 0.46f)); ice(0.72f) }

            71 -> { rainCloud(); flakes(listOf(0.5f)) }
            73 -> { rainCloud(); flakes(listOf(0.36f, 0.64f)) }
            75 -> { rainCloud(); flakes(listOf(0.26f, 0.5f, 0.74f)) }

            // Kar taneleri (graupel): küçük dolu noktalar.
            77 -> {
                rainCloud()
                for ((x, y) in listOf(0.34f to 0.8f, 0.54f to 0.92f, 0.74f to 0.8f, 0.44f to 0.98f)) {
                    drawCircle(color, radius = s * 0.04f, center = Offset(s * x, s * y))
                }
            }

            80 -> { showerCloud(); drops(listOf(0.5f), len = 0.13f) }
            81 -> { showerCloud(); drops(listOf(0.4f, 0.64f)) }
            82 -> { showerCloud(); drops(listOf(0.32f, 0.5f, 0.68f, 0.86f), len = 0.21f) }
            85 -> { showerCloud(); flakes(listOf(0.54f)) }
            86 -> { showerCloud(); flakes(listOf(0.4f, 0.7f)) }

            95, 96, 99 -> {
                rainCloud()
                val bolt = Path().apply {
                    moveTo(s * 0.56f, s * 0.7f)
                    lineTo(s * 0.44f, s * 0.86f)
                    lineTo(s * 0.58f, s * 0.86f)
                    lineTo(s * 0.46f, s * 0.99f)
                }
                drawPath(bolt, color, style = stroke)
                // Dolu: içi boş daireler; 96 hafif, 99 şiddetli.
                val hail = when (code) {
                    96 -> listOf(0.78f to 0.84f)
                    99 -> listOf(0.24f to 0.84f, 0.78f to 0.84f)
                    else -> emptyList()
                }
                for ((x, y) in hail) drawCircle(color, radius = s * 0.07f, center = Offset(s * x, s * y), style = thin)
            }

            // Bilinmeyen kod: düz bulut.
            else -> cloud(0f, s * 0.82f, s, color, stroke)
        }
    }
}

private fun DrawScope.line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, stroke: Stroke) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
}

private fun DrawScope.sun(c: Offset, r: Float, color: Color, stroke: Stroke) {
    drawCircle(color, radius = r, center = c, style = stroke)
    for (i in 0 until 8) {
        val a = i * PI.toFloat() / 4
        val dir = Offset(cos(a), sin(a))
        line(color, c.x + dir.x * r * 1.5f, c.y + dir.y * r * 1.5f, c.x + dir.x * r * 2.1f, c.y + dir.y * r * 2.1f, stroke)
    }
}

/** Altı kollu kar/buz yıldızı. */
private fun DrawScope.flake(c: Offset, r: Float, color: Color, stroke: Stroke) {
    for (i in 0 until 3) {
        val a = i * PI.toFloat() / 3 + PI.toFloat() / 2
        val dx = cos(a) * r
        val dy = sin(a) * r
        line(color, c.x - dx, c.y - dy, c.x + dx, c.y + dy, stroke)
    }
}

private fun DrawScope.moon(c: Offset, r: Float, color: Color, stroke: Stroke) {
    val outer = Path().apply { addOval(Rect(c, r)) }
    val bite = Path().apply { addOval(Rect(Offset(c.x + r * 0.55f, c.y - r * 0.4f), r * 0.85f)) }
    drawPath(Path.combine(PathOperation.Difference, outer, bite), color, style = stroke)
}

/** Üç yuvarlak ve bir tabandan oluşan bulut; [erase] arkasında kalanı (örn. güneşi) siler. */
private fun DrawScope.cloud(
    left: Float,
    bottom: Float,
    w: Float,
    color: Color,
    stroke: Stroke,
    erase: Boolean = false,
) {
    fun oval(cx: Float, cyFromBottom: Float, r: Float) =
        Path().apply { addOval(Rect(Offset(left + cx * w, bottom - cyFromBottom * w), r * w)) }

    var path = oval(0.28f, 0.2f, 0.2f)
    for (part in listOf(
        oval(0.5f, 0.32f, 0.26f),
        oval(0.74f, 0.18f, 0.18f),
        Path().apply { addRect(Rect(left + 0.28f * w, bottom - 0.3f * w, left + 0.74f * w, bottom)) },
    )) {
        path = Path.combine(PathOperation.Union, path, part)
    }
    if (erase) drawPath(path, Color.Black, blendMode = BlendMode.Clear)
    drawPath(path, color, style = stroke)
}
