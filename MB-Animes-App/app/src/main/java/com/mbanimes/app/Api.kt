package com.mbanimes.app

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ApiException(val code: String, message: String, val status: Int) : IOException(message)

/** Sessão do utilizador (token da API). */
object Session {
    private lateinit var prefs: android.content.SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString("token", null)
        set(v) {
            prefs.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply()
        }

    var username: String?
        get() = prefs.getString("username", null)
        set(v) {
            prefs.edit().apply { if (v == null) remove("username") else putString("username", v) }.apply()
        }

    val loggedIn: Boolean get() = !token.isNullOrEmpty()
}

/**
 * Cliente da API /api/v1 (chamadas bloqueantes: usar fora da thread principal).
 * Erros: ApiException (resposta da API) ou IOException (rede).
 */
object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = call(path, query, null)

    fun post(path: String, body: JSONObject = JSONObject()): JSONObject = call(path, emptyMap(), body)

    fun login(email: String, password: String, device: String): JSONObject {
        val r = post("/auth/login", JSONObject().put("email", email).put("password", password).put("device", device))
        saveSession(r)
        return r
    }

    fun register(username: String, email: String, password: String, device: String): JSONObject {
        val r = post(
            "/auth/register",
            JSONObject().put("username", username).put("email", email).put("password", password).put("device", device)
        )
        saveSession(r)
        return r
    }

    fun logout() {
        try {
            post("/auth/logout")
        } catch (e: IOException) {
            // sem rede: apaga na mesma o token local
        }
        Session.token = null
        Session.username = null
    }

    private fun saveSession(r: JSONObject) {
        Session.token = r.getString("token")
        Session.username = r.getJSONObject("user").optString("username")
    }

    private fun call(path: String, query: Map<String, String>, body: JSONObject?): JSONObject {
        val url = Site.url("/api/v1$path", query).toHttpUrl()
        val rb = okhttp3.Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", Net.userAgent)
        Session.token?.let { rb.header("X-Auth-Token", it) }
        if (body != null) rb.post(body.toString().toRequestBody(JSON))

        return Net.client.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException("bad_response", "Resposta inválida do servidor (HTTP ${r.code})", r.code)
            }
            if (!r.isSuccessful) {
                val err = json.optJSONObject("error")
                if (r.code == 401) Session.token = null
                throw ApiException(
                    err?.optString("code") ?: "http_${r.code}",
                    err?.optString("message") ?: "Erro ${r.code}",
                    r.code
                )
            }
            json
        }
    }
}
