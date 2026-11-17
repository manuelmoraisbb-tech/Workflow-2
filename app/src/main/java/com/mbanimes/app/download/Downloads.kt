package com.mbanimes.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Fila de downloads: um episódio de cada vez (os segmentos HLS descarregam em paralelo). */
object Downloads {
    private val executor = Executors.newSingleThreadExecutor()
    private val controls = ConcurrentHashMap<String, DownloadControl>()
    private val outstanding = AtomicInteger(0)

    fun busy(): Boolean = outstanding.get() > 0

    /** Pasta privada do app: não precisa de permissão de armazenamento e é apagada ao desinstalar. */
    fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        return File(base, "MBAnimes").apply { mkdirs() }
    }

    fun start(ctx: Context, item: DownloadItem) {
        if (controls.containsKey(item.id)) return
        val app = ctx.applicationContext
        DownloadStore.put(item.copy(state = DlState.QUEUED, error = null, waitingWifi = false))
        val ctrl = DownloadControl()
        controls[item.id] = ctrl
        outstanding.incrementAndGet()
        DownloadService.ensure(app)
        executor.execute { runItem(app, item.id, ctrl) }
    }

    fun pause(id: String) {
        val c = controls[id]
        if (c != null) {
            c.stop()
        } else {
            DownloadStore.update(id) { if (it.state == DlState.QUEUED) it.copy(state = DlState.PAUSED) else it }
        }
    }

    fun resume(ctx: Context, id: String) {
        val it = DownloadStore.get(id) ?: return
        if (it.state == DlState.PAUSED || it.state == DlState.FAILED) start(ctx, it)
    }

    fun delete(ctx: Context, id: String) {
        controls[id]?.let {
            it.remove = true
            it.stop()
        }
        DownloadStore.get(id)?.filePath?.let { File(it).delete() }
        wipe(ctx, id)
        DownloadStore.remove(id)
    }

    /** Apaga ficheiros temporários (parciais) de um download. */
    fun wipe(ctx: Context, id: String) {
        val d = dir(ctx)
        File(d, "$id.part").delete()
        File(d, "$id.join").delete()
        File(d, "$id.seg").deleteRecursively()
    }

    private fun runItem(ctx: Context, id: String, ctrl: DownloadControl) {
        try {
            val item = DownloadStore.get(id) ?: return
            if (ctrl.stopped) {
                if (!ctrl.remove) DownloadStore.update(id) { it.copy(state = DlState.PAUSED) }
                return
            }
            if (DownloadStore.wifiOnly && isMetered(ctx)) {
                DownloadStore.update(id) { it.copy(state = DlState.PAUSED, waitingWifi = true) }
                return
            }
            DownloadStore.update(id) { it.copy(state = DlState.DOWNLOADING, error = null, waitingWifi = false) }
            val engine = DownloadEngine(dir(ctx), ctrl) { bytes, total, pct ->
                DownloadStore.update(id, persist = false) { it.copy(bytes = bytes, total = total, progress = pct) }
                DownloadService.progress(ctx, DownloadStore.get(id)?.title.orEmpty(), pct)
            }
            val file = engine.run(item)
            DownloadStore.update(id) {
                it.copy(
                    state = DlState.DONE, progress = 100, filePath = file.absolutePath,
                    bytes = file.length(), total = file.length(), error = null
                )
            }
        } catch (e: Exception) {
            if (ctrl.stopped || e is CancelledException) {
                if (!ctrl.remove) DownloadStore.update(id) { it.copy(state = DlState.PAUSED) }
            } else {
                DownloadStore.update(id) { it.copy(state = DlState.FAILED, error = friendly(e)) }
            }
        } finally {
            controls.remove(id)
            if (ctrl.remove) wipe(ctx, id)
            if (outstanding.decrementAndGet() == 0) DownloadService.stopLater(ctx)
        }
    }

    private fun isMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "sem ligação à internet"
        is SocketTimeoutException -> "tempo esgotado"
        else -> e.message ?: e.javaClass.simpleName
    }
}
