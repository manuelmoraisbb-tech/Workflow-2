package com.mbanimes.app

import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * O InfinityFree responde a clientes sem JavaScript com uma página que calcula o cookie "__test" (AES-CBC).
 * Este interceptor faz o mesmo cálculo no telemóvel, guarda o cookie e repete o pedido.
 * Se o InfinityFree mudar o desafio, é aqui que se ajusta (ou muda-se a API para outro alojamento).
 */
class ChallengeInterceptor(
    private val prefs: SharedPreferences,
    private val siteHost: String
) : Interceptor {

    @Volatile
    private var cookie: String? = prefs.getString(KEY, null)

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.host.equals(siteHost, ignoreCase = true)) return chain.proceed(req)

        val first = chain.proceed(withCookie(req))
        if (!isHtml(first)) return first

        val html = first.peekBody(16 * 1024).string()
        if (!html.contains("slowAES")) return first
        val solved = solve(html) ?: return first

        first.close()
        cookie = solved
        prefs.edit().putString(KEY, solved).apply()
        return chain.proceed(withCookie(req))
    }

    private fun isHtml(r: Response) = r.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)

    private fun withCookie(req: Request): Request {
        val c = cookie ?: return req
        val existing = req.header("Cookie").orEmpty()
        val merged = when {
            existing.contains("__test=") -> existing.replace(Regex("__test=[^;]*"), "__test=$c")
            existing.isEmpty() -> "__test=$c"
            else -> "$existing; __test=$c"
        }
        return req.newBuilder().header("Cookie", merged).build()
    }

    /** Página: toNumbers("chave"), toNumbers("iv"), toNumbers("cifrado") → cookie = hex(AES-CBC-decrypt). */
    private fun solve(html: String): String? {
        val parts = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)").findAll(html).map { it.groupValues[1] }.toList()
        if (parts.size < 3) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hex(parts[0]), "AES"), IvParameterSpec(hex(parts[1])))
            cipher.doFinal(hex(parts[2])).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val KEY = "test_cookie"
    }
}
