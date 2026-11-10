package com.example.simplelauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import androidx.core.graphics.drawable.toBitmap
import android.os.Process
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
)

/** Uygulamanın uzun basınca açılan menüde gösterilen kısayolu (ör. "Yeni mesaj"). */
@Immutable
data class AppShortcut(
    val label: String,
    val icon: ImageBitmap?,
    val info: ShortcutInfo,
)

@Immutable
data class Folder(
    val name: String,
    val apps: List<String>,
)

/** Ana ekran sırasında klasörler bu önekle tutulur; paket adlarında ':' olamayacağı için çakışmaz. */
const val FOLDER_PREFIX = "folder:"

fun isFolderKey(key: String) = key.startsWith(FOLDER_PREFIX)

/**
 * Yüklü uygulamaları okur; ana ekran sırasını (paket adı / klasör anahtarı listesi) ve klasörleri saklar.
 */
class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)
    private val user = Process.myUserHandle()
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    fun loadInstalled(): Map<String, AppEntry> {
        val result = LinkedHashMap<String, AppEntry>()
        for (info in launcherApps.getActivityList(null, user)) {
            val pkg = info.componentName.packageName
            if (pkg == context.packageName || pkg in result) continue
            val icon = iconCache.getOrPut(pkg) {
                roundedIcon(info.getIcon(0), ICON_PX).asImageBitmap()
            }
            result[pkg] = AppEntry(pkg, info.label.toString(), info.componentName, icon)
        }
        return result
    }

    fun invalidate(pkg: String) {
        iconCache.remove(pkg)
    }

    fun launch(entry: AppEntry) {
        launcherApps.startMainActivity(entry.component, user, null, null)
    }

    /**
     * Uygulamanın statik ve dinamik kısayolları (en fazla [MAX_SHORTCUTS]). Yalnızca varsayılan başlatıcı
     * okuyabilir; değilsek ya da hata olursa boş liste döner.
     */
    fun shortcuts(entry: AppEntry): List<AppShortcut> = runCatching {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC,
            )
        val density = context.resources.displayMetrics.densityDpi
        launcherApps.getShortcuts(query, user).orEmpty()
            .filter { it.isEnabled && (it.activity == null || it.activity == entry.component) }
            // Önce manifestte tanımlılar, sonra dinamikler; her grup kendi sırasında.
            .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }))
            .take(MAX_SHORTCUTS)
            .map { info ->
                val label = (info.shortLabel ?: info.longLabel)?.toString().orEmpty()
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(info, density)?.toBitmap(SHORTCUT_ICON_PX, SHORTCUT_ICON_PX)?.asImageBitmap()
                }.getOrNull()
                AppShortcut(label, icon, info)
            }
    }.getOrDefault(emptyList())

    fun startShortcut(shortcut: AppShortcut) {
        runCatching { launcherApps.startShortcut(shortcut.info, null, null) }
    }

    fun openAppInfo(entry: AppEntry) {
        launcherApps.startAppDetailsActivity(entry.component, user, null, null)
    }

    fun loadOrder(): List<String> =
        prefs.getString(KEY_ORDER, "").orEmpty().split("\n").filter { it.isNotBlank() }

    fun loadFolders(): Map<String, Folder> {
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val result = LinkedHashMap<String, Folder>()
            for (key in json.keys()) {
                val obj = json.getJSONObject(key)
                val arr = obj.getJSONArray("apps")
                result[key] = Folder(obj.optString("name"), List(arr.length()) { arr.getString(it) })
            }
            result
        }.getOrDefault(emptyMap())
    }

    fun saveLayout(order: List<String>, folders: Map<String, Folder>) {
        val json = JSONObject()
        for ((key, folder) in folders) {
            json.put(key, JSONObject().put("name", folder.name).put("apps", JSONArray(folder.apps)))
        }
        prefs.edit()
            .putString(KEY_ORDER, order.joinToString("\n"))
            .putString(KEY_FOLDERS, json.toString())
            .apply()
    }

    fun register(callback: LauncherApps.Callback) {
        launcherApps.registerCallback(callback)
    }

    fun unregister(callback: LauncherApps.Callback) {
        launcherApps.unregisterCallback(callback)
    }

    private companion object {
        const val KEY_ORDER = "order"
        const val KEY_FOLDERS = "folders"
        const val ICON_PX = 192
        const val SHORTCUT_ICON_PX = 96
        const val MAX_SHORTCUTS = 4
    }
}
