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
}
