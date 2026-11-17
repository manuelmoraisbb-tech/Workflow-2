package com.mbanimes.app.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Guarda os vídeos na pasta pública "Downloads/MB Animes" do aparelho (visível na app Ficheiros/Galeria).
 * Android 10+: MediaStore (sem permissões). Android 7–9: ficheiro direto (pede permissão de armazenamento).
 */
object PublicStorage {
    private const val FOLDER = "MB Animes"

    fun needsLegacyPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Copia o ficheiro para Downloads/MB Animes. Devolve o endereço (content:// ou file://) ou null se falhar. */
    fun export(ctx: Context, src: File): String? = try {
        val mime = mimeOf(src)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportQ(ctx, src, mime) else exportLegacy(ctx, src, mime)
    } catch (e: Exception) {
        null
    }

    private fun exportQ(ctx: Context, src: File, mime: String): String? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("sem stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(ctx: Context, src: File, mime: String): String? {
        if (needsLegacyPermission(ctx)) return null
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        var dest = File(dir, src.name)
        if (dest.exists()) dest = File(dir, src.nameWithoutExtension + " (" + System.currentTimeMillis() % 10000 + ")." + src.extension)
        src.copyTo(dest, overwrite = false)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(dest).toString()
    }

    fun exists(ctx: Context, uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (u.scheme == "file") {
            File(u.path.orEmpty()).exists()
        } else {
            ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }
    } catch (e: Exception) {
        false
    }

    fun delete(ctx: Context, uri: String) {
        try {
            val u = Uri.parse(uri)
            if (u.scheme == "file") File(u.path.orEmpty()).delete() else ctx.contentResolver.delete(u, null, null)
        } catch (e: Exception) {
            // já não existe / sem acesso
        }
    }

    /** O episódio está pronto e o ficheiro ainda existe (na pasta pública ou na privada)? */
    fun available(ctx: Context, d: DownloadItem): Boolean = when {
        d.state != DlState.DONE -> false
        d.uri != null -> exists(ctx, d.uri)
        else -> d.filePath?.let { File(it).exists() } == true
    }

    /** Endereço para reproduzir (content://, file:// ou caminho privado). */
    fun playable(d: DownloadItem): String? = d.uri ?: d.filePath

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: when (f.extension.lowercase()) {
                "ts" -> "video/mp2t"
                "mkv" -> "video/x-matroska"
                else -> "video/mp4"
            }
}
