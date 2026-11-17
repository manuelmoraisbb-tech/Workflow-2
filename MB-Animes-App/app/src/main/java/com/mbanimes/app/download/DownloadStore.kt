package com.mbanimes.app.download

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Lista de downloads guardada em JSON (sem Room, para manter o build simples). */
object DownloadStore {
    private lateinit var file: File
    private lateinit var prefs: SharedPreferences
    private val items = LinkedHashMap<String, DownloadItem>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(v) {
            prefs.edit().putBoolean("wifi_only", v).apply()
        }

    @Synchronized
    fun init(ctx: Context) {
        if (this::file.isInitialized) return
        file = File(ctx.filesDir, "downloads.json")
        prefs = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        load()
    }

    @Synchronized
    fun all(): List<DownloadItem> = items.values.sortedByDescending { it.createdAt }

    @Synchronized
    fun get(id: String): DownloadItem? = items[id]

    @Synchronized
    fun put(item: DownloadItem) {
        items[item.id] = item
        save()
        fire()
    }

    /** persist=false para o progresso (muitas atualizações); true nas mudanças de estado. */
    @Synchronized
    fun update(id: String, persist: Boolean = true, f: (DownloadItem) -> DownloadItem) {
        val cur = items[id] ?: return
        items[id] = f(cur)
        if (persist) save()
        fire()
    }

    @Synchronized
    fun remove(id: String) {
        items.remove(id)
        save()
        fire()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun fire() {
        if (pending.compareAndSet(false, true)) {
            main.postDelayed({
                pending.set(false)
                listeners.forEach { it() }
            }, 300)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var state = try {
                    DlState.valueOf(o.optString("state", "PAUSED"))
                } catch (e: Exception) {
                    DlState.PAUSED
                }
                // Se a app foi fechada a meio, o download fica em pausa (o utilizador retoma).
                if (state == DlState.DOWNLOADING || state == DlState.QUEUED) state = DlState.PAUSED
                val it = DownloadItem(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    episode = o.optString("episode"),
                    poster = o.optString("poster"),
                    url = o.optString("url"),
                    pageUrl = o.optString("pageUrl"),
                    createdAt = o.optLong("createdAt"),
                    state = state,
                    progress = o.optInt("progress"),
                    bytes = o.optLong("bytes"),
                    total = o.optLong("total"),
                    filePath = o.optString("filePath").ifEmpty { null },
                    uri = o.optString("uri").ifEmpty { null },
                    error = o.optString("error").ifEmpty { null }
                )
                items[it.id] = it
            }
        } catch (e: Exception) {
            // ficheiro corrompido: recomeça vazio
            items.clear()
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (it in items.values) {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("title", it.title).put("episode", it.episode)
                        .put("poster", it.poster).put("url", it.url).put("pageUrl", it.pageUrl)
                        .put("createdAt", it.createdAt).put("state", it.state.name)
                        .put("progress", it.progress).put("bytes", it.bytes).put("total", it.total)
                        .put("filePath", it.filePath ?: "").put("uri", it.uri ?: "").put("error", it.error ?: "")
                )
            }
            val tmp = File(file.parentFile, "downloads.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            // sem espaço/permissões: ignora, a lista em memória continua válida
        }
    }
}
