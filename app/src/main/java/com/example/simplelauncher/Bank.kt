package com.example.simplelauncher

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.util.Base64
import android.webkit.WebSettings
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

@Immutable
data class Money(val amount: String, val currency: String)

/** Bakiye eşiğe göre hangi renkte gösterilir. */
enum class BalanceLevel { Red, Yellow, Green }

/**
 * Bir bankadaki bir para biriminin eşikleri; boş olan kapalıdır. Bakiye [red] altındaysa kırmızı,
 * değilse [yellow] altındaysa sarı, değilse [green] ve üstündeyse yeşil gösterilir.
 */
@Immutable
data class Thresholds(val red: BigDecimal? = null, val yellow: BigDecimal? = null, val green: BigDecimal? = null) {
    fun level(amount: String): BalanceLevel? {
        val value = amount.toBigDecimalOrNull() ?: return null
        return when {
            red != null && value < red -> BalanceLevel.Red
            yellow != null && value < yellow -> BalanceLevel.Yellow
            green != null && value >= green -> BalanceLevel.Green
            else -> null
        }
    }

    val isEmpty: Boolean get() = red == null && yellow == null && green == null
}

/** Bir bankadaki tüm hesapların bakiyeleri (Revolut/Wise'ta her para birimi ayrı hesaptır). */
@Immutable
data class BankBalance(
    val amounts: List<Money>,
    val fetchedAt: Long,
)

enum class BankStatus { NotConfigured, NotConnected, Expired, Loading, Ok, Failed }

/** Banka onayı bitti ya da geri alındı; yeniden bağlanmak gerekiyor. */
class BankSessionExpired : Exception()

/**
 * Bir bankanın ([bank], Enable Banking'deki adı: "N26", "Revolut", "Wise") bakiyesini Enable Banking
 * (PSD2, yalnızca okuma) üzerinden çeker. Her bankanın onayı ve hesapları ayrı saklanır.
 * Uygulama kimliği ve özel anahtar secrets.properties'ten BuildConfig'e gelir; repoya girmez.
 */
class BankRepository(private val context: Context, val bank: String) {

    // N26 tek banka olduğu zamanki dosyada kalır ki mevcut onay yeniden istenmesin.
    private val prefs = context.getSharedPreferences(
        if (bank == LEGACY_BANK) "bank" else "bank_" + bank.lowercase(Locale.ROOT),
        Context.MODE_PRIVATE,
    )
    private val privateKey: PrivateKey? by lazy { runCatching { parsePrivateKey(BuildConfig.EB_PRIVATE_KEY) }.getOrNull() }

    val configured: Boolean
        get() = BuildConfig.EB_APP_ID.isNotBlank() && privateKey != null

    val redirectUrl: String
        get() = BuildConfig.EB_REDIRECT_URL

    fun hasSession(): Boolean = accounts().isNotEmpty()

    /** Bakiyesi gösterilecek hesaplar; eski sürüm yalnızca ilk hesabı saklıyordu. */
    private fun accounts(): List<String> =
        prefs.getString(KEY_ACCOUNTS, null)?.split("\n")?.filter { it.isNotBlank() }
            ?: listOfNotNull(prefs.getString(KEY_ACCOUNT, null))

    /** Yeni oturum kaydedilince [onChange] çağrılır; dönen fonksiyon dinlemeyi bırakır. */
    fun onSessionChanged(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SESSION) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun sessionExpired(): Boolean = System.currentTimeMillis() > prefs.getLong(KEY_VALID_UNTIL, 0)

    fun cached(): BankBalance? {
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0)
        prefs.getString(KEY_BALANCES, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                val amounts = List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    Money(o.getString("amount"), o.getString("currency"))
                }
                return BankBalance(amounts, fetchedAt)
            }
        }
        val amount = prefs.getString(KEY_AMOUNT, null) ?: return null
        return BankBalance(listOf(Money(amount, prefs.getString(KEY_CURRENCY, "EUR")!!)), fetchedAt)
    }

    /** Banka onay sayfasının adresini döner; kullanıcı orada hesaplarına erişime izin verir. */
    suspend fun startAuth(): String = withContext(Dispatchers.IO) {
        val state = UUID.randomUUID().toString()
        val body = JSONObject()
            .put(
                "access",
                JSONObject().put("valid_until", OffsetDateTime.now(ZoneOffset.UTC).plusDays(CONSENT_DAYS).toString()),
            )
            .put(
                "aspsp",
                JSONObject().put("name", bank).put("country", BuildConfig.EB_ASPSP_COUNTRY),
            )
            .put("state", state)
            .put("redirect_url", redirectUrl)
            .put("psu_type", "personal")
        prefs.edit().putString(KEY_STATE, state).apply()
        request("POST", "/auth", body).getString("url")
    }

    /** Onaydan dönen kodla oturum açar ve izin verilen tüm hesapları bakiye için saklar. */
    suspend fun completeAuth(code: String, state: String?) = withContext(Dispatchers.IO) {
        if (state != null && state != prefs.getString(KEY_STATE, null)) error("state uyuşmuyor")
        val session = request("POST", "/sessions", JSONObject().put("code", code))
        val list = session.getJSONArray("accounts")
        val accounts = List(list.length()) { list.getJSONObject(it).getString("uid") }
        if (accounts.isEmpty()) error("hesap yok")
        val validUntil = session.optJSONObject("access")?.optString("valid_until")
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: (System.currentTimeMillis() + CONSENT_DAYS * DAY_MS)
        prefs.edit()
            .putString(KEY_SESSION, session.getString("session_id"))
            .putString(KEY_ACCOUNTS, accounts.joinToString("\n"))
            .remove(KEY_ACCOUNT)
            .putLong(KEY_VALID_UNTIL, validUntil)
            .remove(KEY_STATE)
            .apply()
    }

    /**
     * Bankalar kullanıcı yokken günde ~4 sorguya izin verir. [psuPresent] ise istek kullanıcının
     * cihaz bilgileriyle gönderilir; banka bunu kullanıcının başlattığı sorgu sayar, sınıra saymaz.
     */
    suspend fun fetchBalance(psuPresent: Boolean = false): BankBalance = withContext(Dispatchers.IO) {
        val accounts = accounts().ifEmpty { throw BankSessionExpired() }
        val headers = if (psuPresent) psuHeaders() else emptyMap()
        val all = accounts.mapNotNull { account ->
            val balances = request("GET", "/accounts/$account/balances", null, headers).getJSONArray("balances")
            val list = List(balances.length()) { balances.getJSONObject(it) }
            // Kullanılabilir bakiye öncelikli; yoksa defter bakiyesi, o da yoksa ilk gelen.
            val chosen = BALANCE_PRIORITY.firstNotNullOfOrNull { type -> list.firstOrNull { it.optString("balance_type") == type } }
                ?: list.firstOrNull()
                ?: return@mapNotNull null
            val money = chosen.getJSONObject("balance_amount")
            Money(money.getString("amount"), money.getString("currency"))
        }
        if (all.isEmpty()) error("bakiye yok")
        // Boş para birimi hesapları satırı kalabalıklaştırmasın; hepsi boşsa yine de biri görünür.
        val amounts = all.filter { it.amount.toBigDecimalOrNull()?.signum() != 0 }.ifEmpty { all.take(1) }
        val balance = BankBalance(amounts, System.currentTimeMillis())
        val json = JSONArray(amounts.map { JSONObject().put("amount", it.amount).put("currency", it.currency) })
        prefs.edit()
            .putString(KEY_BALANCES, json.toString())
            .putLong(KEY_FETCHED_AT, balance.fetchedAt)
            .remove(KEY_AMOUNT)
            .remove(KEY_CURRENCY)
            .apply()
        balance
    }

    private fun psuHeaders(): Map<String, String> = buildMap {
        deviceIp()?.let { put("Psu-Ip-Address", it) }
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()?.let { put("Psu-User-Agent", it) }
        put("Psu-Accept-Language", Locale.getDefault().toLanguageTag())
    }

    /** Etkin ağdaki IPv4 adresi (yoksa IPv6). */
    private fun deviceIp(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val addresses = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses.orEmpty()
            .map { it.address }
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val conn = URL(API + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer ${jwt()}")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) throw BankSessionExpired()
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (error.contains("EXPIRED_SESSION") || error.contains("CLOSED_SESSION")) throw BankSessionExpired()
                error("HTTP $code $error")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Enable Banking her istekte uygulamanın anahtarıyla imzalanmış kısa ömürlü bir JWT ister. */
    private fun jwt(): String {
        val key = privateKey ?: error("anahtar yok")
        val now = System.currentTimeMillis() / 1000
        val header = JSONObject().put("typ", "JWT").put("alg", "RS256").put("kid", BuildConfig.EB_APP_ID)
        val claims = JSONObject()
            .put("iss", "enablebanking.com")
            .put("aud", "api.enablebanking.com")
            .put("iat", now)
            .put("exp", now + 3600)
        val unsigned = b64(header.toString().toByteArray()) + "." + b64(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(unsigned.toByteArray())
            sign()
        }
        return unsigned + "." + b64(signature)
    }

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        private const val LEGACY_BANK = "N26"

        /** Bakiyelerin gizli olup olmadığı tüm bankalar için tek ayardır. */
        fun hiddenPrefs(context: Context): SharedPreferences =
            context.getSharedPreferences("bank", Context.MODE_PRIVATE)

        const val KEY_HIDDEN = "hidden"
        private const val API = "https://api.enablebanking.com"
        // N26, Revolut ve Wise en fazla 180 gün kabul ediyor; saat farkı sınırı aşmasın diye bir gün pay.
        private const val CONSENT_DAYS = 179L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val BALANCE_PRIORITY = listOf("ITAV", "CLAV", "XPCD", "ITBD", "CLBD")

        private const val KEY_STATE = "state"
        private const val KEY_SESSION = "session"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_VALID_UNTIL = "validUntil"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_CURRENCY = "currency"
        private const val KEY_BALANCES = "balances"
        private const val KEY_FETCHED_AT = "fetchedAt"
    }
}

/** PEM özel anahtarı okur; PKCS#8 ("PRIVATE KEY") ya da PKCS#1 ("RSA PRIVATE KEY") olabilir. */
private fun parsePrivateKey(pem: String): PrivateKey {
    val der = Base64.decode(
        pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim(),
        Base64.DEFAULT,
    )
    val pkcs8 = if (pem.contains("BEGIN RSA PRIVATE KEY")) pkcs1ToPkcs8(der) else der
    return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
}

/** PKCS#1 RSA anahtarını PKCS#8 zarfına sarar (Java yalnızca PKCS#8 okur). */
private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
    // AlgorithmIdentifier: rsaEncryption OID + NULL
    val algorithm = byteArrayOf(
        0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
        0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
    )
    val version = byteArrayOf(0x02, 0x01, 0x00)
    val octet = der(0x04, pkcs1)
    return der(0x30, version + algorithm + octet)
}

private fun der(tag: Int, content: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(tag)
    val len = content.size
    when {
        len < 0x80 -> out.write(len)
        len < 0x100 -> { out.write(0x81); out.write(len) }
        len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
        else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xff); out.write(len and 0xff) }
    }
    out.write(content)
    return out.toByteArray()
}
