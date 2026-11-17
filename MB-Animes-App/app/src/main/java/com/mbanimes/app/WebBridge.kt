package com.mbanimes.app

import android.webkit.JavascriptInterface
import com.mbanimes.app.download.DlState
import com.mbanimes.app.download.DownloadItem
import com.mbanimes.app.download.DownloadStore
import com.mbanimes.app.download.Downloads
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Ponte entre o JavaScript do site (window.MBApp) e o gestor de downloads nativo. */
class WebBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun download(json: String): String {
        if (!activity.isTrustedPage()) return "error:pagina-nao-confiavel"
        return try {
            val o = JSONObject(json)
            val url = o.getString("url")
            if (!(url.startsWith("https://") || url.startsWith("http://"))) return "error:link-invalido"

            val slug = o.optString("slug")
            val season = o.optInt("season")
            val ep = o.optInt("ep")
            val id = if (slug.isNotEmpty() && ep > 0) {
                "${slug}_${season}_${ep}".replace(Regex("[^A-Za-z0-9_-]"), "-")
            } else {
                sha1(url)
            }

            val ctx = activity.applicationContext
            val old = DownloadStore.get(id)
            if (old != null) {
                if (old.state == DlState.DONE && old.filePath?.let { File(it).exists() } == true) return "exists"
                if (old.state == DlState.QUEUED || old.state == DlState.DOWNLOADING) return "busy"
                if (old.url != url) Downloads.wipe(ctx, id) // fonte diferente: não reaproveita parciais
            }

            val fresh = DownloadItem(
                id = id,
                title = o.optString("title").ifEmpty { slug },
                episode = o.optString("episode"),
                poster = o.optString("poster"),
                url = url,
                pageUrl = o.optString("page"),
                createdAt = System.currentTimeMillis()
            )
            val base = if (old != null && old.url == url && old.state != DlState.DONE) old else fresh

            activity.runOnUiThread { activity.ensureNotificationPermission() }
            Downloads.start(ctx, base)
            "ok"
        } catch (e: Exception) {
            "error:" + (e.message ?: "falha")
        }
    }

    @JavascriptInterface
    fun status(id: String): String =
        DownloadStore.get(id)?.state?.name?.lowercase() ?: "none"

    @JavascriptInterface
    fun openDownloads() {
        if (!activity.isTrustedPage()) return
        activity.runOnUiThread { activity.showDownloads() }
    }

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
