package com.mbanimes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mbanimes.app.databinding.ActivityMainBinding
import com.mbanimes.app.download.DlState
import com.mbanimes.app.download.DownloadItem
import com.mbanimes.app.download.DownloadStore
import com.mbanimes.app.download.Downloads
import com.mbanimes.app.download.DownloadsAdapter
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val siteUrl: String = BuildConfig.SITE_URL
    private val siteHost: String = Uri.parse(BuildConfig.SITE_URL).host.orEmpty()

    @Volatile
    private var currentUrl: String = ""
    private var hasError = false
    private var clearHistoryOnFinish = false
    private var suppressNav = false

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var chrome: WebChromeClient? = null
    private var imeVisible = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val adapter = DownloadsAdapter(
        onPrimary = { onDownloadPrimary(it) },
        onDelete = { confirmDelete(it) }
    )
    private val storeListener: () -> Unit = { refreshDownloads() }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        applyInsets()
        setupWebView()
        if (BuildConfig.DEBUG) debugApiCheck()
        setupBottomNav()
        setupDownloadsPanel()
        setupBackHandling()

        if (savedInstanceState != null && b.webView.restoreState(savedInstanceState) != null) {
            // página restaurada
            handleIntent(intent, initial = false)
        } else {
            handleIntent(intent, initial = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false)
    }

    override fun onStart() {
        super.onStart()
        DownloadStore.addListener(storeListener)
        refreshDownloads()
    }

    override fun onStop() {
        DownloadStore.removeListener(storeListener)
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        b.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        b.webView.onResume()
    }

    override fun onDestroy() {
        (b.webView.parent as? ViewGroup)?.removeView(b.webView)
        b.webView.stopLoading()
        b.webView.destroy()
        super.onDestroy()
    }

    /** Só em debug: confirma que a API responde (e que o desafio do InfinityFree foi resolvido). */
    private fun debugApiCheck() {
        Thread {
            val msg = try {
                val c = Api.get("/config")
                "API OK (v" + c.optInt("apiVersion") + ")"
            } catch (e: Exception) {
                "API falhou: " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    // ------------------------------------------------------------------ layout / insets

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeVisible = ime.bottom > 0
            v.setPadding(bars.left, bars.top, bars.right, if (imeVisible) ime.bottom else 0)
            b.bottomNav.visibility = if (imeVisible || customView != null) View.GONE else View.VISIBLE
            insets
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = b.webView
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            // o site deteta "MBAnimesApp" para esconder o rodapé e ativar o gestor de downloads
            userAgentString = userAgentString + " MBAnimesApp/" + BuildConfig.VERSION_NAME
        }
        Net.userAgent = wv.settings.userAgentString
        wv.addJavascriptInterface(WebBridge(this), "MBApp")
        wv.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                hasError = false
                currentUrl = url.orEmpty()
                b.refresh.isRefreshing = true
                syncTab(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url.orEmpty()
                b.refresh.isRefreshing = false
                if (clearHistoryOnFinish) {
                    clearHistoryOnFinish = false
                    view?.clearHistory()
                }
                syncTab(url)
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                // só mostra a página de erro se falhar a página principal
                if (request?.isForMainFrame == true) {
                    hasError = true
                    view?.loadDataWithBaseURL(null, offlineHtml, "text/html", "UTF-8", null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val s = uri.toString()
                if (s.startsWith("mbapp://downloads")) {
                    showDownloads()
                    return true
                }
                if (s.startsWith("mbapp://retry")) {
                    hasError = false
                    view?.loadUrl(siteUrl)
                    return true
                }
                if ((uri.scheme == "http" || uri.scheme == "https") && isSiteUri(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                recreate()
                return true
            }
        }

        chrome = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null || view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                b.refresh.visibility = View.GONE
                b.bottomNav.visibility = View.GONE
                b.fullscreenContainer.visibility = View.VISIBLE
                b.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                WindowInsetsControllerCompat(window, b.root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                b.fullscreenContainer.removeView(v)
                b.fullscreenContainer.visibility = View.GONE
                if (b.downloadsPanel.isVisible) b.downloadsPanel.visibility = View.VISIBLE
                else b.refresh.visibility = View.VISIBLE
                b.bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
                customCallback?.onCustomViewHidden()
                customView = null
                customCallback = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowInsetsControllerCompat(window, b.root).show(WindowInsetsCompat.Type.systemBars())
            }
        }
        wv.webChromeClient = chrome

        // puxar para baixo para atualizar (só quando a página está no topo)
        b.refresh.setColorSchemeResources(R.color.primary)
        b.refresh.setProgressBackgroundColorSchemeResource(R.color.surface)
        b.refresh.setOnChildScrollUpCallback { _, _ -> b.webView.scrollY > 0 }
        b.refresh.setOnRefreshListener {
            if (hasError) b.webView.loadUrl(siteUrl) else b.webView.reload()
        }
    }

    private val offlineHtml = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="font-family:sans-serif;text-align:center;padding:64px 24px;background:#000;color:#eee">
        <h2>Sem ligação</h2>
        <p style="color:#a0a0a0">Verifica a tua internet e tenta de novo.</p>
        <p><a href="mbapp://retry" style="display:inline-block;padding:12px 28px;background:#ff640a;color:#000;font-weight:700;border-radius:999px;text-decoration:none">Tentar de novo</a></p>
        <p><a href="mbapp://downloads" style="color:#ff640a">Ver downloads offline</a></p>
        </body></html>
    """.trimIndent()

    fun isTrustedPage(): Boolean {
        val u = Uri.parse(currentUrl)
        return u.scheme == "https" && u.host.equals(siteHost, ignoreCase = true)
    }

    private fun isSiteUri(u: Uri) = u.host.equals(siteHost, ignoreCase = true)

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            // nenhuma app para abrir este link
        }
    }

    private fun handleIntent(i: Intent?, initial: Boolean) {
        val data = i?.data
        if (data != null && isSiteUri(data)) {
            hideDownloads()
            b.webView.loadUrl(data.toString())
        } else if (initial) {
            b.webView.loadUrl(siteUrl)
        }
        if (i?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) showDownloads()
    }

    // ------------------------------------------------------------------ separadores

    private fun setupBottomNav() {
        b.bottomNav.setOnItemSelectedListener { item ->
            if (suppressNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.tab_home -> { hideDownloads(); loadTab("/") }
                R.id.tab_catalog -> { hideDownloads(); loadTab("/catalogo") }
                R.id.tab_library -> { hideDownloads(); loadTab("/biblioteca") }
                R.id.tab_downloads -> showDownloads()
            }
            true
        }
        b.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId != R.id.tab_downloads) b.webView.scrollTo(0, 0)
        }
    }

    private fun loadTab(path: String) {
        val target = siteUrl.trimEnd('/') + path
        if (currentUrl.trimEnd('/') == target.trimEnd('/')) {
            b.webView.scrollTo(0, 0)
        } else {
            clearHistoryOnFinish = true
            b.webView.loadUrl(target)
        }
    }

    private fun selectTab(id: Int) {
        if (b.bottomNav.selectedItemId != id) {
            suppressNav = true
            b.bottomNav.selectedItemId = id
            suppressNav = false
        }
    }

    /** Mantém o separador selecionado de acordo com a página aberta no site. */
    private fun syncTab(url: String?) {
        if (url == null || b.downloadsPanel.isVisible) return
        val u = Uri.parse(url)
        if (!isSiteUri(u)) return
        val p = u.path.orEmpty()
        b.refresh.isEnabled = !p.startsWith("/assistir") // evita recarregar sem querer durante o vídeo
        val tab: Int? = when {
            p.startsWith("/catalogo") -> R.id.tab_catalog
            p.startsWith("/biblioteca") -> R.id.tab_library
            p == "/" || p.isEmpty() -> R.id.tab_home
            else -> null
        }
        if (tab != null) selectTab(tab)
    }

    // ------------------------------------------------------------------ downloads (painel nativo)

    private fun setupDownloadsPanel() {
        b.downloadsList.layoutManager = LinearLayoutManager(this)
        b.downloadsList.adapter = adapter
        b.wifiSwitch.isChecked = DownloadStore.wifiOnly
        b.wifiSwitch.setOnCheckedChangeListener { _, checked -> DownloadStore.wifiOnly = checked }
    }

    fun showDownloads() {
        b.webView.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){v.pause()})", null
        )
        b.refresh.visibility = View.GONE
        b.downloadsPanel.visibility = View.VISIBLE
        selectTab(R.id.tab_downloads)
        refreshDownloads()
    }

    private fun hideDownloads() {
        if (b.downloadsPanel.isVisible) {
            b.downloadsPanel.visibility = View.GONE
            b.refresh.visibility = View.VISIBLE
        }
    }

    private fun refreshDownloads() {
        val list = DownloadStore.all()
        adapter.submitList(list)
        b.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val active = list.count { it.state == DlState.DOWNLOADING || it.state == DlState.QUEUED }
        val badge = b.bottomNav.getOrCreateBadge(R.id.tab_downloads)
        badge.isVisible = active > 0
        if (active > 0) badge.number = active
    }

    private fun onDownloadPrimary(d: DownloadItem) {
        when (d.state) {
            DlState.DONE -> {
                val f = d.filePath?.let { File(it) }
                if (f == null || !f.exists()) {
                    Toast.makeText(this, R.string.file_missing, Toast.LENGTH_LONG).show()
                    return
                }
                startActivity(PlayerActivity.intent(this, d.id, d.title + " · " + d.episode, f.absolutePath))
            }
            DlState.QUEUED, DlState.DOWNLOADING -> Downloads.pause(d.id)
            DlState.PAUSED, DlState.FAILED -> {
                ensureNotificationPermission()
                Downloads.resume(applicationContext, d.id)
            }
        }
    }

    private fun confirmDelete(d: DownloadItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, d.title + " " + d.episode))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> Downloads.delete(applicationContext, d.id) }
            .show()
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------ botão voltar

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chrome?.onHideCustomView()
                    b.downloadsPanel.isVisible -> {
                        hideDownloads()
                        syncTab(currentUrl)
                    }
                    b.webView.canGoBack() && !hasError -> b.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
