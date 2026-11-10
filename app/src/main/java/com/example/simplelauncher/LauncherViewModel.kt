package com.example.simplelauncher

import android.app.Application
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)
    private val locationSource = LocationSource(application)
    private val weatherRepo = WeatherRepository(application)
    private val prayerRepo = PrayerRepository(application)
    private val bankHiddenPrefs = BankRepository.hiddenPrefs(application)

    /**
     * Ana ekrandaki sıra: düz bir liste (paket adı ya da klasör anahtarı).
     * Sayfa ve hücre konumu bu listeden hesaplanır.
     */
    val order = mutableStateListOf<String>()

    /** Klasör anahtarı → klasör. Klasördeki uygulamalar [order] içinde ayrıca yer almaz. */
    val folders = mutableStateMapOf<String, Folder>()

    var weather by mutableStateOf(weatherRepo.cached())
        private set

    var weatherStatus by mutableStateOf(WeatherStatus.Loading)
        private set

    private val marketRepo = MarketRepository(application)

    /** Enflasyon ve piyasa performans tablosu. */
    var markets by mutableStateOf(marketRepo.cached())
        private set
    private var marketJob: Job? = null

    /** Piyasa verisini en fazla [MARKET_TTL_MS]'de bir yeniler; [force] kullanıcının isteğidir. */
    fun refreshMarkets(force: Boolean = false) {
        if (marketJob?.isActive == true) return
        val last = markets
        if (!force && last != null && System.currentTimeMillis() - last.fetchedAt < MARKET_TTL_MS) return
        marketJob = viewModelScope.launch {
            try {
                markets = marketRepo.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "piyasa verisi alınamadı", e)
            }
        }
    }

    var prayers by mutableStateOf(prayerRepo.cached())
        private set

    /** Ana ekranda alt alta gösterilen bankalar (secrets.properties'teki sırayla). */
    val banks: List<BankSlot> = BuildConfig.EB_ASPSPS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .map { BankSlot(BankRepository(application, it)) }

    var bankHidden by mutableStateOf(bankHiddenPrefs.getBoolean(BankRepository.KEY_HIDDEN, false))
        private set

    var apps by mutableStateOf<Map<String, AppEntry>>(emptyMap())
        private set

    /** Home tuşuna basılınca artar; arayüz ilk sayfaya döner ve düzenleme modunu kapatır. */
    var homeSignal by mutableIntStateOf(0)
        private set

    private var refreshJob: Job? = null
    private var weatherJob: Job? = null

    /** Onay sayfası oturumu kurunca (launcher o sırada açık olsa bile) o bankanın bakiyesini hemen çek. */
    private val stopBankWatches = banks.map { slot -> slot.repo.onSessionChanged { refreshBank(slot, force = true) } }
    private var loaded = false

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            repo.invalidate(packageName)
            refresh()
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            repo.invalidate(packageName)
            refresh()
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            repo.invalidate(packageName)
            refresh()
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach { repo.invalidate(it) }
            refresh()
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            refresh()
        }
    }

    init {
        repo.register(callback)
        refresh()
        refreshWeather()
        refreshBank()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val installed = withContext(Dispatchers.Default) { repo.loadInstalled() }
            val savedOrder = if (loaded) order.toList() else repo.loadOrder()
            val savedFolders = if (loaded) folders.toMap() else repo.loadFolders()
            loaded = true

            // Klasörlerden silinen uygulamaları at; boş kalan klasörleri kaldır.
            val inFolder = HashSet<String>()
            val cleanFolders = LinkedHashMap<String, Folder>()
            for ((key, folder) in savedFolders) {
                val kept = folder.apps.filter { it in installed && inFolder.add(it) }
                if (kept.isNotEmpty()) cleanFolders[key] = folder.copy(apps = kept)
            }

            // Silinenleri at, yeni yüklenenleri sona ekle (ilk açılışta alfabetik başlar).
            val kept = savedOrder.filter {
                if (isFolderKey(it)) it in cleanFolders else it in installed && it !in inFolder
            }.distinct()
            val keptSet = kept.toSet()
            val orphanFolders = cleanFolders.keys.filter { it !in keptSet }
            val added = installed.values
                .filter { it.packageName !in keptSet && it.packageName !in inFolder }
                .sortedBy { it.label.lowercase() }
                .map { it.packageName }
            val merged = kept + orphanFolders + added

            apps = installed
            if (cleanFolders != folders.toMap()) {
                folders.clear()
                folders.putAll(cleanFolders)
            }
            if (merged != order.toList()) {
                order.clear()
                order.addAll(merged)
            }
            save()
        }
    }

    /** Sürüklenen uygulamayı listede yeni yerine taşır; diğerleri kayarak yer açar. */
    fun move(pkg: String, toIndex: Int) {
        val from = order.indexOf(pkg)
        if (from < 0 || order.isEmpty()) return
        val to = toIndex.coerceIn(0, order.lastIndex)
        if (from == to) return
        order.removeAt(from)
        order.add(to, pkg)
    }

    fun commitOrder() = save()

    /**
     * Sürüklenen uygulamayı hedefin üstüne bırakır: hedef klasörse içine eklenir,
     * uygulamaysa ikisinden yeni bir klasör oluşur. Yeni klasörün anahtarını döner.
     */
    fun merge(dragKey: String, targetKey: String): String? {
        if (isFolderKey(dragKey) || dragKey == targetKey || dragKey !in order) return null
        val targetIndex = order.indexOf(targetKey)
        if (targetIndex < 0) return null

        if (isFolderKey(targetKey)) {
            val folder = folders[targetKey] ?: return null
            folders[targetKey] = folder.copy(apps = folder.apps + dragKey)
            order.remove(dragKey)
            save()
            return null
        }

        val key = FOLDER_PREFIX + System.currentTimeMillis()
        folders[key] = Folder(DEFAULT_FOLDER_NAME, listOf(targetKey, dragKey))
        order[targetIndex] = key
        order.remove(dragKey)
        save()
        return key
    }

    /** Klasör içinde sürüklenen uygulamayı yeni yerine taşır; kaydetmek için [commitOrder]. */
    fun moveInFolder(key: String, pkg: String, toIndex: Int) {
        val folder = folders[key] ?: return
        val from = folder.apps.indexOf(pkg)
        if (from < 0) return
        val to = toIndex.coerceIn(0, folder.apps.lastIndex)
        if (from == to) return
        val apps = folder.apps.toMutableList()
        apps.removeAt(from)
        apps.add(to, pkg)
        folders[key] = folder.copy(apps = apps)
    }

    fun renameFolder(key: String, name: String) {
        val folder = folders[key] ?: return
        folders[key] = folder.copy(name = name)
        save()
    }

    /** Uygulamayı klasörden çıkarıp ana ekranda klasörün hemen arkasına koyar. */
    fun removeFromFolder(key: String, pkg: String) {
        val folder = folders[key] ?: return
        val rest = folder.apps - pkg
        val index = order.indexOf(key)
        if (rest.isEmpty()) {
            folders.remove(key)
            if (index >= 0) order[index] = pkg else order.add(pkg)
        } else {
            folders[key] = folder.copy(apps = rest)
            order.add(if (index >= 0) index + 1 else order.size, pkg)
        }
        save()
    }

    /** Klasörü dağıtır; içindeki uygulamalar klasörün yerine sırayla yerleşir. */
    fun dissolveFolder(key: String) {
        val folder = folders.remove(key) ?: return
        val index = order.indexOf(key)
        if (index >= 0) {
            order.removeAt(index)
            order.addAll(index, folder.apps)
        } else {
            order.addAll(folder.apps)
        }
        save()
    }

    fun launch(pkg: String) {
        apps[pkg]?.let { repo.launch(it) }
    }

    /** Uzun basma menüsündeki kısayollar; IPC ve ikon yükleme olduğu için arka planda okunur. */
    suspend fun shortcuts(pkg: String): List<AppShortcut> {
        val entry = apps[pkg] ?: return emptyList()
        return withContext(Dispatchers.IO) { repo.shortcuts(entry) }
    }

    fun startShortcut(shortcut: AppShortcut) = repo.startShortcut(shortcut)

    fun openAppInfo(pkg: String) {
        apps[pkg]?.let { repo.openAppInfo(it) }
    }

    /**
     * Konumu alıp hava durumunu ve ezan vakitlerini yeniler. [force] yoksa hava durumu yeterince yeni ve
     * ezan vakitleri bugüne aitse bir şey yapmaz. Konum değiştiyse ezan vakitleri yeniden çekilir.
     */
    fun refreshWeather(force: Boolean = false) {
        if (!locationSource.hasPermission()) {
            weatherStatus = WeatherStatus.NeedsPermission
            return
        }
        if (weatherJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val last = weather
        val weatherFresh = !force && last != null && now - last.fetchedAt < WEATHER_TTL_MS
        val schedule = prayers
        val prayersFresh = !force && schedule != null &&
            schedule.day == LocalDate.now().toString() && schedule.next(now) != null
        if (weatherFresh && prayersFresh) {
            weatherStatus = WeatherStatus.Ok
            return
        }
        weatherJob = viewModelScope.launch {
            if (!weatherFresh) weatherStatus = WeatherStatus.Loading
            val loc = locationSource.current()
            if (loc == null) {
                if (!weatherFresh) weatherStatus = WeatherStatus.Failed
                return@launch
            }
            if (!weatherFresh) {
                weatherStatus = try {
                    weather = weatherRepo.fetch(loc)
                    WeatherStatus.Ok
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WeatherStatus.Failed
                }
            }
            val current = prayers
            if (force || current == null || !prayerRepo.isFresh(current, loc) ||
                current.next(System.currentTimeMillis()) == null
            ) {
                try {
                    prayers = prayerRepo.fetch(loc)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Eski vakitler gösterilmeye devam eder; bir sonraki yenilemede tekrar denenir.
                }
            }
        }
    }

    /**
     * Tüm bankaların bakiyesini yeniler. PSD2 kullanıcı başında değilken günde 4 çekmeye izin verdiği için
     * [force] yoksa en fazla 6 saatte bir çeker. [force] her zaman kullanıcının kendi isteğidir
     * (uzun basma, tekrar dene, yeni bağlantı); bu yüzden banka sınırına sayılmasın diye
     * istek "kullanıcı ekranda" olarak işaretlenir.
     */
    fun refreshBank(force: Boolean = false) {
        banks.forEach { refreshBank(it, force) }
    }

    fun refreshBank(slot: BankSlot, force: Boolean = false) {
        val repo = slot.repo
        slot.status = when {
            !repo.configured -> BankStatus.NotConfigured
            !repo.hasSession() -> BankStatus.NotConnected
            repo.sessionExpired() -> BankStatus.Expired
            else -> {
                if (slot.job?.isActive == true) return
                val last = slot.balance
                if (!force && last != null && System.currentTimeMillis() - last.fetchedAt < BANK_TTL_MS) {
                    BankStatus.Ok
                } else {
                    slot.job = viewModelScope.launch {
                        slot.status = BankStatus.Loading
                        slot.status = try {
                            slot.balance = repo.fetchBalance(psuPresent = force)
                            BankStatus.Ok
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: BankSessionExpired) {
                            BankStatus.Expired
                        } catch (e: Exception) {
                            Log.w(TAG, "${repo.bank} bakiyesi alınamadı", e)
                            BankStatus.Failed
                        }
                    }
                    BankStatus.Loading
                }
            }
        }
    }

    /** Banka onay sayfasının adresini hazırlar; hazır olunca [onUrl] ile açılır. */
    fun connectBank(slot: BankSlot, onUrl: (url: String, redirect: String) -> Unit) {
        val repo = slot.repo
        if (!repo.configured) return
        slot.job?.cancel()
        slot.job = viewModelScope.launch {
            slot.status = BankStatus.Loading
            try {
                onUrl(repo.startAuth(), repo.redirectUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${repo.bank} onayı başlatılamadı", e)
                slot.status = BankStatus.Failed
            }
        }
    }

    fun toggleBankHidden() {
        bankHidden = !bankHidden
        bankHiddenPrefs.edit().putBoolean(BankRepository.KEY_HIDDEN, bankHidden).apply()
    }

    private val thresholdPrefs = application.getSharedPreferences("bank_thresholds", android.content.Context.MODE_PRIVATE)

    /** Banka + para birimi başına renk eşikleri; anahtar "Banka|EUR". */
    val thresholds = mutableStateMapOf<String, Thresholds>().apply {
        for ((key, raw) in thresholdPrefs.all) {
            val json = runCatching { org.json.JSONObject(raw as String) }.getOrNull() ?: continue
            fun num(name: String) = json.optString(name).takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
            put(key, Thresholds(num("red"), num("yellow"), num("green")))
        }
    }

    fun thresholdsFor(bank: String, currency: String): Thresholds = thresholds["$bank|$currency"] ?: Thresholds()

    fun setThresholds(bank: String, currency: String, value: Thresholds) {
        val key = "$bank|$currency"
        if (value.isEmpty) {
            thresholds.remove(key)
            thresholdPrefs.edit().remove(key).apply()
            return
        }
        thresholds[key] = value
        val json = org.json.JSONObject()
        value.red?.let { json.put("red", it.toPlainString()) }
        value.yellow?.let { json.put("yellow", it.toPlainString()) }
        value.green?.let { json.put("green", it.toPlainString()) }
        thresholdPrefs.edit().putString(key, json.toString()).apply()
    }

    private fun save() {
        repo.saveLayout(order.toList(), folders.toMap())
    }

    fun onHomePressed() {
        homeSignal++
    }

    override fun onCleared() {
        repo.unregister(callback)
        stopBankWatches.forEach { it() }
    }

    private companion object {
        const val TAG = "SimpleLauncher"
        const val DEFAULT_FOLDER_NAME = "Folder"
        const val WEATHER_TTL_MS = 20 * 60 * 1000L
        const val BANK_TTL_MS = 6 * 60 * 60 * 1000L
        const val MARKET_TTL_MS = 3 * 60 * 60 * 1000L
    }
}

/** Ana ekrandaki bir banka satırının durumu. */
class BankSlot(val repo: BankRepository) {
    val name: String get() = repo.bank
    var balance by mutableStateOf(repo.cached())
        internal set
    var status by mutableStateOf(BankStatus.Loading)
        internal set
    internal var job: Job? = null
}
