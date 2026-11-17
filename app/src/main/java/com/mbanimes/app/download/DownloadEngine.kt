package com.mbanimes.app.download

import com.mbanimes.app.Net
import okhttp3.Call
import okhttp3.HttpUrl
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CancelledException : IOException("cancelado")

/** Permite pausar/cancelar um download em curso (cancela os pedidos HTTP pendentes). */
class DownloadControl {
    @Volatile
    var stopped = false

    @Volatile
    var remove = false
    private val calls = CopyOnWriteArrayList<Call>()

    fun track(c: Call) {
        calls.add(c)
        if (stopped) c.cancel()
    }

    fun untrack(c: Call) {
        calls.remove(c)
    }

    fun stop() {
        stopped = true
        calls.forEach { it.cancel() }
    }

    fun check() {
        if (stopped) throw CancelledException()
    }
}

/**
 * Descarrega um episódio: ficheiro direto (mp4/mkv, com retoma por Range) ou HLS (m3u8 → junta os segmentos).
 * Devolve o ficheiro final. Pode lançar IOException (ou CancelledException se pausado/cancelado).
 */
class DownloadEngine(
    private val dir: File,
    private val control: DownloadControl,
    private val report: (bytes: Long, total: Long, pct: Int) -> Unit
) {
    @Volatile
    private var lastReport = 0L

    private fun progress(bytes: Long, total: Long, pct: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReport < 500) return
        lastReport = now
        report(bytes, total, pct)
    }

    private fun pct(done: Long, total: Long): Int =
        if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0

    fun run(item: DownloadItem): File {
        if (dir.usableSpace < 80L * 1024 * 1024) throw IOException("pouco espaço livre no dispositivo")
        val part = File(dir, "${item.id}.part")
        val existing = if (part.exists()) part.length() else 0L

        val rb = Net.request(item.url)
        if (existing > 0) rb.header("Range", "bytes=$existing-")
        val call = Net.client.newCall(rb.build())
        control.track(call)
        val result: File
        try {
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                control.check()
                throw e
            }
            result = resp.use { r -> handle(item, r, part, existing) }
        } finally {
            control.untrack(call)
        }
        return result
    }

    private fun handle(item: DownloadItem, r: okhttp3.Response, part: File, existing: Long): File {
        control.check()
        val ct = r.header("Content-Type").orEmpty().lowercase()
        // pedido de retoma além do fim: o ficheiro já estava completo
        if (r.code == 416 && existing > 0) return finish(item, part, ct)
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        val body = r.body ?: throw IOException("resposta vazia")

        val head = r.peekBody(16).string()
        if (head.startsWith("#EXTM3U") || ct.contains("mpegurl")) {
            return hls(item, r.request.url, body.string())
        }
        if (ct.contains("text/html")) {
            throw IOException("o servidor devolveu uma página HTML em vez do vídeo (erro do site ou proteção anti-bot da hospedagem)")
        }

        val append = r.code == 206 && existing > 0
        var done = if (append) existing else 0L
        val total = if (r.code == 206) {
            Regex("/(\\d+)\\s*$").find(r.header("Content-Range").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        } else {
            body.contentLength().coerceAtLeast(0L)
        }
        progress(done, total, pct(done, total), true)

        FileOutputStream(part, append).use { out ->
            val buf = ByteArray(64 * 1024)
            val input = body.byteStream()
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    control.check()
                    throw e
                }
                if (n < 0) break
                control.check()
                out.write(buf, 0, n)
                done += n
                progress(done, total, pct(done, total))
            }
        }
        control.check()
        if (total > 0 && part.length() < total) throw IOException("download incompleto: toca em retomar")
        return finish(item, part, ct)
    }

    // ---------------------------------------------------------------- HLS

    private class Seg(val url: String, val key: String?, val iv: ByteArray?, val seq: Long)

    private fun hls(item: DownloadItem, playlistUrl: HttpUrl, text0: String): File {
        var base = playlistUrl
        var text = text0

        if (text.contains("#EXT-X-STREAM-INF")) { // playlist mestre: escolhe a melhor qualidade
            val lines = text.lines()
            var best: String? = null
            var bw = -1L
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val b = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size && b > bw) {
                    bw = b
                    best = lines[j].trim()
                }
            }
            val pick = best ?: throw IOException("playlist sem qualidades")
            base = base.resolve(pick) ?: throw IOException("link de qualidade inválido")
            text = fetchText(base.toString())
        }

        val mediaSeq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val segs = ArrayList<Seg>()
        var initUrl: String? = null
        var key: String? = null
        var iv: ByteArray? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#")) {
                if (l.startsWith("#EXT-X-KEY")) {
                    when (attr(l, "METHOD")) {
                        "AES-128" -> {
                            val uri = attr(l, "URI") ?: throw IOException("chave de vídeo em falta")
                            key = base.resolve(uri)?.toString() ?: throw IOException("chave de vídeo inválida")
                            iv = attr(l, "IV")?.let { hexToBytes(it) }
                        }
                        "NONE", null -> {
                            key = null
                            iv = null
                        }
                        else -> throw IOException("vídeo protegido (DRM)")
                    }
                } else if (l.startsWith("#EXT-X-MAP")) {
                    initUrl = attr(l, "URI")?.let { base.resolve(it)?.toString() }
                }
                continue
            }
            val u = base.resolve(l)?.toString() ?: continue
            segs.add(Seg(u, key, iv, mediaSeq + segs.size))
        }
        if (segs.isEmpty()) throw IOException("playlist sem segmentos")

        val segDir = File(dir, "${item.id}.seg").apply { mkdirs() }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val doneCount = AtomicInteger(0)
        val doneBytes = AtomicLong(0)
        val n = segs.size
        val pool = Executors.newFixedThreadPool(3)
        try {
            val init = initUrl
            if (init != null) {
                val f = File(segDir, "init.bin")
                if (!f.exists() || f.length() == 0L) writeAtomic(f, fetchBytes(init))
            }
            val futures = segs.mapIndexed { i, s ->
                pool.submit(Callable<Unit> {
                    val f = File(segDir, "%06d.seg".format(i))
                    if (!(f.exists() && f.length() > 0)) {
                        var data = fetchBytes(s.url)
                        val k = s.key
                        if (k != null) {
                            val keyBytes = keys.getOrPut(k) { fetchBytes(k) }
                            data = decrypt(data, keyBytes, s.iv ?: seqIv(s.seq))
                        }
                        writeAtomic(f, data)
                    }
                    doneBytes.addAndGet(f.length())
                    val c = doneCount.incrementAndGet()
                    progress(doneBytes.get(), 0L, (c * 100 / n).coerceAtMost(99))
                })
            }
            try {
                futures.forEach { it.get() }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } catch (e: Throwable) {
            pool.shutdownNow()
            throw e
        } finally {
            pool.shutdown()
        }

        control.check()
        val joined = File(dir, "${item.id}.join")
        FileOutputStream(joined, false).use { out ->
            if (initUrl != null) File(segDir, "init.bin").inputStream().use { it.copyTo(out) }
            for (i in 0 until n) {
                control.check()
                File(segDir, "%06d.seg".format(i)).inputStream().use { it.copyTo(out) }
            }
        }
        segDir.deleteRecursively()
        val dest = uniqueDest(item, if (initUrl != null) "mp4" else "ts")
        if (!joined.renameTo(dest)) {
            joined.copyTo(dest, overwrite = true)
            joined.delete()
        }
        progress(dest.length(), dest.length(), 100, true)
        return dest
    }

    // ---------------------------------------------------------------- utilitários

    private fun finish(item: DownloadItem, part: File, ct: String): File {
        val dest = uniqueDest(item, guessExt(item.url, ct))
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        progress(dest.length(), dest.length(), 100, true)
        return dest
    }

    private fun guessExt(url: String, ct: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        for (e in listOf("mp4", "mkv", "webm", "m4v", "ts")) if (path.endsWith(".$e")) return e
        return when {
            ct.contains("matroska") -> "mkv"
            ct.contains("webm") -> "webm"
            else -> "mp4"
        }
    }

    private fun uniqueDest(item: DownloadItem, ext: String): File {
        val base = (item.title + " " + item.episode)
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().take(80).ifEmpty { "episodio" }
        var f = File(dir, "$base.$ext")
        if (f.exists()) f = File(dir, "$base (${item.id.take(8)}).$ext")
        return f
    }

    private fun fetchBytes(url: String): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            control.check()
            val call = Net.client.newCall(Net.request(url).build())
            control.track(call)
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    if (r.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                        throw IOException("o servidor devolveu uma página HTML (proteção anti-bot ou link expirado)")
                    }
                    return r.body?.bytes() ?: throw IOException("resposta vazia")
                }
            } catch (e: IOException) {
                control.check()
                last = e
            } finally {
                control.untrack(call)
            }
        }
        throw last ?: IOException("falha no pedido")
    }

    private fun fetchText(url: String): String = String(fetchBytes(url), Charsets.UTF_8)

    private fun writeAtomic(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) {
            f.writeBytes(data)
            tmp.delete()
        }
    }

    private fun attr(line: String, name: String): String? {
        val m = Regex("[:,]" + name + "=(\"([^\"]*)\"|([^,]*))").find(line) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { null }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun seqIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (seq shr (8 * i)).toByte()
        return iv
    }

    private fun hexToBytes(h: String): ByteArray {
        val s = h.trim().removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
