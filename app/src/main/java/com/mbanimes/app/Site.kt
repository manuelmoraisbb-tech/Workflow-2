package com.mbanimes.app

import android.net.Uri

/** Constrói e lê endereços do site, com ou sem URLs bonitos (index.php?r=/rota). */
object Site {
    private val root = BuildConfig.SITE_URL.trimEnd('/')

    /** Endereço completo de uma rota lógica, ex.: "/catalogo" ou "/api/v1/home". */
    fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val b = if (BuildConfig.PRETTY_URLS) {
            Uri.parse(root + p).buildUpon()
        } else {
            Uri.parse("$root/index.php").buildUpon().appendQueryParameter("r", p)
        }
        query.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return b.build().toString()
    }

    /** Rota lógica de um endereço do site ("/" para a home), seja qual for o formato. */
    fun logicalPath(uri: Uri): String {
        val r = uri.getQueryParameter("r")
        if (!r.isNullOrEmpty()) return "/" + r.trim('/')
        val p = uri.path.orEmpty().removeSuffix("/index.php").trimEnd('/')
        return if (p.isEmpty()) "/" else p
    }

    private val rootHost: String = Uri.parse(BuildConfig.SITE_URL).host.orEmpty()
    private val fileExt = Regex(
        "\\.(php|js|css|map|png|jpe?g|gif|webp|svg|ico|json|xml|txt|mp4|m3u8|ts|woff2?|ttf)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Sem rewrite no servidor, um link "bonito" (ex.: /assistir/naruto/1/3, vindo de JS ou de dados
     * guardados no aparelho) cai no index.php sem rota e abre a página inicial.
     * Devolve o endereço corrigido (index.php?r=/assistir/naruto/1/3) ou null se já está certo.
     */
    fun normalize(uri: Uri): String? {
        if (BuildConfig.PRETTY_URLS) return null
        if (!uri.host.equals(rootHost, ignoreCase = true)) return null
        if (!uri.getQueryParameter("r").isNullOrEmpty()) return null

        var path = uri.path.orEmpty().trimEnd('/')
        if (path.startsWith("/index.php/")) path = path.removePrefix("/index.php")
        if (path.isEmpty() || path == "/index.php") return null
        if (fileExt.containsMatchIn(path)) return null

        val query = LinkedHashMap<String, String>()
        for (name in uri.queryParameterNames) {
            uri.getQueryParameter(name)?.let { query[name] = it }
        }
        return url(path, query)
    }
}
