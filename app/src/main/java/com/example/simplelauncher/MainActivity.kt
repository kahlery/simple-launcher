package com.example.simplelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()
    private val uiPrefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.refreshWeather(force = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            HomeScreen(
                vm = vm,
                onRemoveApp = ::uninstall,
                onRequestLocation = ::requestLocation,
                onSwipeDown = ::expandNotifications,
                onConnectBank = ::connectBank,
            )
        }
        // Hava durumu için ilk açılışta bir kez konum izni iste.
        if (savedInstanceState == null && !uiPrefs.getBoolean(KEY_ASKED_LOCATION, false)) {
            requestLocation()
        }
    }

    override fun onStart() {
        super.onStart()
        vm.refresh()
        vm.refreshWeather()
        vm.refreshBank()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home tuşuna tekrar basılınca ilk sayfaya dön.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            vm.onHomePressed()
        }
    }

    /** Sistemin kendi silme onayını açar; onaylanınca uygulama gider ve ana ekrandan da kalkar. */
    private fun uninstall(pkg: String) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    /** Yukarıdan inen bildirim panelini açar (EXPAND_STATUS_BAR izni; resmi API olmadığı için yansıma ile). */
    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        runCatching {
            val statusBar = getSystemService("statusbar")
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
        }
    }

    private fun connectBank(slot: BankSlot) {
        if (BankAuthActivity.pending == slot.name) {
            startActivity(BankAuthActivity.resume(this))
            return
        }
        vm.connectBank(slot) { url, redirect -> startActivity(BankAuthActivity.intent(this, slot.name, url, redirect)) }
    }

    /** Konum iznini ister; sistem artık sormuyorsa uygulama ayarlarını açar. */
    private fun requestLocation() {
        val asked = uiPrefs.getBoolean(KEY_ASKED_LOCATION, false)
        if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else {
            uiPrefs.edit().putBoolean(KEY_ASKED_LOCATION, true).apply()
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private companion object {
        const val KEY_ASKED_LOCATION = "askedLocation"
    }
}
