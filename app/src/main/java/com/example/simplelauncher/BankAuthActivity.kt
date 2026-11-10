package com.example.simplelauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Banka onay sayfasını gösterir. Onaydan sonra banka, kayıtlı yönlendirme adresine gider;
 * o adres hiç açılmadan yakalanır ve oturum burada kurulur.
 *
 * Kendi görevinde (task) çalışır: banka onayı kendi uygulamasında istediğinde kullanıcı
 * ana ekrana dönse bile sayfa kapanmaz, onay gelince arka planda tamamlanır.
 */
class BankAuthActivity : Activity() {

    private val scope = MainScope()
    private var done = false
    private var bank = ""
    private var redirect = ""
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (redirect.isNotEmpty() && url.toString().startsWith(redirect)) {
                    complete(url)
                    return true
                }
                // Banka onayı kendi uygulamasında istiyorsa (intent://, n26:// gibi) onu aç.
                if (url.scheme != "http" && url.scheme != "https") {
                    runCatching {
                        startActivity(
                            if (url.scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                            else Intent(Intent.ACTION_VIEW, url),
                        )
                    }
                    return true
                }
                return false
            }
        }
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    /** Sayfa açıkken başka bir banka bağlanmak istenirse aynı ekranda onun onayına geçilir. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        setIntent(intent)
        readIntent(intent)
        done = false
        web.loadUrl(url)
    }

    private fun readIntent(intent: Intent) {
        bank = intent.getStringExtra(EXTRA_BANK).orEmpty()
        redirect = intent.getStringExtra(EXTRA_REDIRECT).orEmpty()
        pending = bank
    }

    private fun complete(url: Uri) {
        if (done) return
        done = true
        val bank = bank
        val code = url.getQueryParameter("code")
        if (code == null) {
            Log.w(TAG, "$bank onayı kodsuz döndü: ${url.getQueryParameter("error")}")
            finish()
            return
        }
        scope.launch {
            try {
                BankRepository(applicationContext, bank).completeAuth(code, url.getQueryParameter("state"))
            } catch (e: Exception) {
                Log.w(TAG, "$bank oturumu açılamadı", e)
            }
            // Ana ekrana dön; launcher oturumu görünce bakiyeyi çeker.
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName))
            finish()
        }
    }

    override fun onDestroy() {
        if (pending == bank) pending = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SimpleLauncher"
        private const val EXTRA_URL = "url"
        private const val EXTRA_REDIRECT = "redirect"
        private const val EXTRA_BANK = "bank"

        /** Onay sayfası açık olan banka; aynı banka için yenisi yerine o öne getirilir. */
        var pending: String? = null
            private set

        fun intent(context: Context, bank: String, url: String, redirect: String) =
            resume(context)
                .putExtra(EXTRA_BANK, bank)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_REDIRECT, redirect)

        fun resume(context: Context) =
            Intent(context, BankAuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
