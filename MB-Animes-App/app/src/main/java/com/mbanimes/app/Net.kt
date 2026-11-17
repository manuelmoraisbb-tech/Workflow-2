package com.mbanimes.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Cliente HTTP partilhado (API + downloads). Resolve o desafio do InfinityFree e usa cache HTTP (ETag). */
object Net {
    @Volatile
    var userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 MBAnimesApp"

    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    val client: OkHttpClient by lazy {
        val siteHost = Uri.parse(BuildConfig.SITE_URL).host.orEmpty()
        OkHttpClient.Builder()
            .cache(Cache(File(appContext.cacheDir, "http"), 20L * 1024 * 1024))
            .addInterceptor(
                ChallengeInterceptor(appContext.getSharedPreferences("net", Context.MODE_PRIVATE), siteHost)
            )
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Pedido com User-Agent e, se existirem, os cookies do WebView (o interceptor completa o resto). */
    fun request(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        val cookie = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            null
        }
        if (!cookie.isNullOrEmpty()) b.header("Cookie", cookie)
        return b
    }
}
