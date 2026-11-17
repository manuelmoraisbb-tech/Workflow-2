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
import android.text.format.Formatter
import android.util.Log
import android.view.HapticFeedbackConstants
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
import android.widget.PopupMenu
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
import com.mbanimes.app.download.PublicStorage
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val siteUrl: String = BuildConfig.SITE_URL
    private val siteHost: String = Uri.parse(BuildConfig.SITE_URL).host.orEmpty()

    @Volatile
    private var currentUrl: String = ""
    private var webTitle: String = ""
    private var failedUrl: String? = null
    private var clearHistoryOnFinish = false
    private var suppressNav = false
    private var focusSearchOnFinish = false
    private var showSiteChrome = false

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var chrome: WebChromeClient? = null
    private var imeVisible = false

    private val appModeJs: String by lazy {
        try {
            assets.open("app-mode.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private val hideSkeletonRunnable = Runnable { hideSkeleton() }

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
        setupTopBar()
        setupWebView()
        if (BuildConfig.DEBUG) debugApiCheck()
        setupBottomNav()
        setupDownloadsPanel()
        setupErrorView()
        setupBackHandling()

        if (savedInstanceState != null && b.webView.restoreState(savedInstanceState) != null) {
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
        b.skeleton.removeCallbacks(hideSkeletonRunnable)
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
            Log.d("MBAnimes", msg)
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

    // ------------------------------------------------------------------ barra superior nativa

    private fun setupTopBar() {
        b.btnBack.setOnClickListener {
            if (b.webView.canGoBack()) {
                hideError()
                b.webView.goBack()
            } else {
                loadTab("/")
            }
        }
        b.btnSearch.setOnClickListener {
            hideDownloads()
            if (Site.logicalPath(Uri.parse(currentUrl)) == "/catalogo" && isTrustedPage()) {
                focusSearch()
            } else {
                focusSearchOnFinish = true
                loadTab("/catalogo")
            }
        }
        b.btnMore.setOnClickListener { showMoreMenu() }
    }

    private fun showMoreMenu() {
        val pm = PopupMenu(this, b.btnMore)
        pm.menu.add(0, MENU_RELOAD, 0, R.string.menu_reload)
        pm.menu.add(0, MENU_SHARE, 1, R.string.menu_share)
        pm.menu.add(
            0, MENU_SITE_MENU, 2,
            if (showSiteChrome) R.string.menu_site_menu_hide else R.string.menu_site_menu_show
        )
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RELOAD -> {
                    hideDownloads()
                    if (failedUrl != null && b.errorView.isVisible) retry() else b.webView.reload()
                }
                MENU_SHARE -> {
                    val url = currentUrl.ifEmpty { siteUrl }
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, url)
                    startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
                }
                MENU_SITE_MENU -> {
                    showSiteChrome = !showSiteChrome
                    b.webView.evaluateJavascript(
                        "window.__MB_SHOW_CHROME=$showSiteChrome;" +
                            "if(window.__mbApp){window.__mbApp.setChrome($showSiteChrome);}", null
                    )
                }
            }
            true
        }
        pm.show()
    }

    private fun focusSearch() {
        b.webView.requestFocus()
        b.webView.evaluateJavascript(
            "(function(){var i=document.querySelector('input[type=search],input[name=q],input[name=busca]," +
                "input[name=search],input[placeholder*=esquis],input[placeholder*=earch]');" +
                "if(i){i.scrollIntoView({block:'center'});i.focus();}})()", null
        )
    }

    private fun isTopLevel(path: String) = path == "/" || path == "/catalogo" || path == "/biblioteca"

    /** Título/botão voltar da barra superior de acordo com a página aberta. */
    private fun updateTopBar() {
        if (b.downloadsPanel.isVisible) {
            b.btnBack.visibility = View.GONE
            b.logo.visibility = View.GONE
            b.pageTitle.setText(R.string.downloads_title)
            return
        }
        val path = Site.logicalPath(Uri.parse(currentUrl.ifEmpty { siteUrl }))
        val top = isTopLevel(path)
        b.btnBack.visibility = if (top) View.GONE else View.VISIBLE
        b.logo.visibility = if (path == "/") View.VISIBLE else View.GONE
        b.pageTitle.text = when (path) {
            "/" -> getString(R.string.app_name)
            "/catalogo" -> getString(R.string.tab_catalog)
            "/biblioteca" -> getString(R.string.tab_library)
            else -> cleanTitle(webTitle)
        }
    }

    private fun cleanTitle(raw: String): String {
        val t = raw.replace(Regex("\\s*[|\\-–—·]\\s*MB\\s*Animes.*$", RegexOption.IGNORE_CASE), "").trim()
        return if (t.isEmpty() || t.contains("://") || t.contains(".php")) getString(R.string.app_name) else t
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = b.webView
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            setSupportZoom(false)
            // o site deteta "MBAnimesApp" para esconder o rodapé e ativar o gestor de downloads
            userAgentString = userAgentString + " MBAnimesApp/" + BuildConfig.VERSION_NAME
        }
        Net.userAgent = wv.settings.userAgentString
        wv.addJavascriptInterface(WebBridge(this), "MBApp")
        wv.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                currentUrl = url.orEmpty()
                hideError()
                showLoading()
                syncTab(url)
                injectAppMode(view, url)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                injectAppMode(view, url)
                hideSkeleton()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url.orEmpty()
                b.refresh.isRefreshing = false
                b.loadBar.visibility = View.INVISIBLE
                if (clearHistoryOnFinish) {
                    clearHistoryOnFinish = false
                    view?.clearHistory()
                }
                syncTab(url)
                injectAppMode(view, url)
                hideSkeleton()
                if (focusSearchOnFinish && Site.logicalPath(Uri.parse(url.orEmpty())) == "/catalogo") {
                    focusSearchOnFinish = false
                    focusSearch()
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                // só mostra o ecrã de erro se falhar a página principal
                if (request?.isForMainFrame == true) showError(request.url?.toString() ?: siteUrl)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val s = uri.toString()
                if (s.startsWith("mbapp://downloads")) {
                    showDownloads()
                    return true
                }
                if (s.startsWith("mbapp://retry")) {
                    retry()
                    return true
                }
                if ((uri.scheme == "http" || uri.scheme == "https") && isSiteUri(uri)) {
                    // corrige links "bonitos" (ex.: do "Continuar a ver") que cairiam na página inicial
                    val fixed = Site.normalize(uri)
                    if (fixed != null) {
                        view?.loadUrl(fixed)
                        return true
                    }
                    return false
                }
                openExternal(uri)
                return true
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                recreate()
                return true
            }
        }

        chrome = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99 && !b.errorView.isVisible) {
                    b.loadBar.visibility = View.VISIBLE
                    b.loadBar.setProgressCompat(newProgress, true)
                } else {
                    b.loadBar.visibility = View.INVISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                webTitle = title.orEmpty()
                updateTopBar()
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null || view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                b.refresh.visibility = View.GONE
                b.topBar.visibility = View.GONE
                b.loadBar.visibility = View.GONE
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
                if (!b.downloadsPanel.isVisible) b.refresh.visibility = View.VISIBLE
                b.topBar.visibility = View.VISIBLE
                b.loadBar.visibility = View.INVISIBLE
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
        b.refresh.setProgressBackgroundColorSchemeResource(R.color.surface_high)
        b.refresh.setOnChildScrollUpCallback { _, _ -> b.webView.scrollY > 0 }
        b.refresh.setOnRefreshListener {
            if (b.errorView.isVisible) retry() else b.webView.reload()
        }
    }

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

    private fun injectAppMode(view: WebView?, url: String?) {
        val u = url ?: return
        if (appModeJs.isEmpty() || !isSiteUri(Uri.parse(u))) return
        view?.evaluateJavascript("window.__MB_SHOW_CHROME=$showSiteChrome;\n$appModeJs", null)
    }

    private fun handleIntent(i: Intent?, initial: Boolean) {
        val data = i?.data
        if (data != null && isSiteUri(data)) {
            hideDownloads()
            b.webView.loadUrl(Site.normalize(data) ?: data.toString())
        } else if (initial) {
            b.webView.loadUrl(siteUrl)
        }
        if (i?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) showDownloads()
    }

    // ------------------------------------------------------------------ carregamento e erro

    private fun showLoading() {
        if (b.refresh.isRefreshing || b.downloadsPanel.isVisible) return
        b.skeleton.removeCallbacks(hideSkeletonRunnable)
        b.skeleton.animate().cancel()
        b.skeleton.alpha = 1f
        b.skeleton.visibility = View.VISIBLE
        b.skeleton.postDelayed(hideSkeletonRunnable, 10_000) // segurança: nunca fica preso
    }

    private fun hideSkeleton() {
        b.skeleton.removeCallbacks(hideSkeletonRunnable)
        if (!b.skeleton.isVisible) return
        b.skeleton.animate().alpha(0f).setDuration(180).withEndAction {
            b.skeleton.visibility = View.GONE
            b.skeleton.alpha = 1f
        }.start()
    }

    private fun showError(url: String) {
        failedUrl = url
        b.refresh.isRefreshing = false
        b.loadBar.visibility = View.INVISIBLE
        b.skeleton.removeCallbacks(hideSkeletonRunnable)
        b.skeleton.animate().cancel()
        b.skeleton.visibility = View.GONE
        b.errorView.visibility = View.VISIBLE
    }

    private fun hideError() {
        if (b.errorView.isVisible) b.errorView.visibility = View.GONE
    }

    private fun retry() {
        hideError()
        b.webView.loadUrl(failedUrl ?: siteUrl)
    }

    private fun setupErrorView() {
        b.errorRetry.setOnClickListener { retry() }
        b.errorDownloads.setOnClickListener { showDownloads() }
    }

    // ------------------------------------------------------------------ separadores

    private fun setupBottomNav() {
        b.bottomNav.setOnItemSelectedListener { item ->
            if (suppressNav) return@setOnItemSelectedListener true
            b.bottomNav.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            when (item.itemId) {
                R.id.tab_home -> { hideDownloads(); loadTab("/") }
                R.id.tab_catalog -> { hideDownloads(); loadTab("/catalogo") }
                R.id.tab_library -> { hideDownloads(); loadTab("/biblioteca") }
                R.id.tab_downloads -> showDownloads()
            }
            true
        }
        b.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId != R.id.tab_downloads) {
                b.webView.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null)
            } else {
                b.downloadsList.smoothScrollToPosition(0)
            }
        }
    }

    private fun loadTab(path: String) {
        hideError()
        if (Site.logicalPath(Uri.parse(currentUrl)) == path && isTrustedPage()) {
            b.webView.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null)
        } else {
            clearHistoryOnFinish = true
            b.webView.loadUrl(Site.url(path))
        }
    }

    private fun selectTab(id: Int) {
        if (b.bottomNav.selectedItemId != id) {
            suppressNav = true
            b.bottomNav.selectedItemId = id
            suppressNav = false
        }
    }

    /** Mantém o separador e a barra superior de acordo com a página aberta no site. */
    private fun syncTab(url: String?) {
        if (url == null || b.downloadsPanel.isVisible) return
        val u = Uri.parse(url)
        if (!isSiteUri(u)) return
        val p = Site.logicalPath(u)
        b.refresh.isEnabled = !p.startsWith("/assistir") // evita recarregar sem querer durante o vídeo
        val tab: Int? = when {
            p.startsWith("/catalogo") -> R.id.tab_catalog
            p.startsWith("/biblioteca") -> R.id.tab_library
            p == "/" || p.isEmpty() -> R.id.tab_home
            else -> null
        }
        if (tab != null) selectTab(tab)
        updateTopBar()
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
        updateTopBar()
        refreshDownloads()
    }

    private fun hideDownloads() {
        if (b.downloadsPanel.isVisible) {
            b.downloadsPanel.visibility = View.GONE
            b.refresh.visibility = View.VISIBLE
            updateTopBar()
        }
    }

    private fun refreshDownloads() {
        val list = DownloadStore.all()
        adapter.submitList(list)
        b.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val done = list.filter { it.state == DlState.DONE }
        b.downloadsSummary.text = if (list.isEmpty()) {
            getString(R.string.downloads_summary_none)
        } else {
            val size = Formatter.formatShortFileSize(this, done.sumOf { it.bytes })
            getString(R.string.downloads_summary, list.size, size)
        }
        val active = list.count { it.state == DlState.DOWNLOADING || it.state == DlState.QUEUED }
        val badge = b.bottomNav.getOrCreateBadge(R.id.tab_downloads)
        badge.isVisible = active > 0
        if (active > 0) badge.number = active
    }

    private fun onDownloadPrimary(d: DownloadItem) {
        when (d.state) {
            DlState.DONE -> {
                val target = PublicStorage.playable(d)
                if (target == null || !PublicStorage.available(this, d)) {
                    Toast.makeText(this, R.string.file_missing, Toast.LENGTH_LONG).show()
                    return
                }
                startActivity(PlayerActivity.intent(this, d.id, d.title + " · " + d.episode, target))
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

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureNotificationPermission() {
        // Android 7–9: precisa de permissão para gravar na pasta Downloads
        if (PublicStorage.needsLegacyPermission(this)) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
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
                    b.webView.canGoBack() -> {
                        hideError()
                        b.webView.goBack()
                    }
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
        private const val MENU_RELOAD = 1
        private const val MENU_SHARE = 2
        private const val MENU_SITE_MENU = 3
    }
}
