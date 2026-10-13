package com.cdhunter.pro.export

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.cdhunter.pro.core.model.ExportFormat
import com.cdhunter.pro.db.ExportLogDao
import com.cdhunter.pro.db.ExportLogEntity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android-side export actions: build → validate → deliver (clipboard, share
 * sheet, saved file, QR bitmaps). Every export is logged locally.
 */
class ExportManager(
    private val context: Context,
    private val exportLogDao: ExportLogDao,
) {

    data class Prepared(
        val format: ExportFormat,
        val text: String,
        val itemCount: Int,
        val validationError: String?,
    )

    suspend fun prepare(format: ExportFormat, text: String, itemCount: Int): Prepared {
        val error = Exporter.validate(format, text)
        return Prepared(format, text, itemCount, error)
    }

    suspend fun copyToClipboard(prep: Prepared): Boolean {
        if (prep.validationError != null) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(
            ClipData(
                ClipDescription("CDNHunter Pro export", arrayOf("text/plain")),
                ClipData.Item(prep.text),
            ),
        )
        log(prep, "clipboard")
        return true
    }

    /** Builds a share intent (user picks destination — file or text). */
    suspend fun share(prep: Prepared): Intent? {
        if (prep.validationError != null) return null
        log(prep, "share")
        if (prep.text.length <= 100_000) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prep.text)
                putExtra(Intent.EXTRA_SUBJECT, "CDNHunter Pro export (${prep.format.name})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val file = persistToCache(prep)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Saves the export as a file the user can open with any file manager.
     * API 29+: Downloads via MediaStore (no permission needed).
     * API 26-28: app-external files dir + returns the file (share recommended).
     */
    suspend fun saveFile(prep: Prepared): File? {
        if (prep.validationError != null) return null
        return withContext(Dispatchers.IO) {
            val name = "cdnhunter_${prep.format.name.lowercase()}_${System.currentTimeMillis()}." +
                if (prep.format == ExportFormat.JSON) "json" else "txt"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        os.write(prep.text.toByteArray(Charsets.UTF_8))
                    }
                }
                File(context.cacheDir, name).apply { writeText(prep.text) } // user-visible copy path for share
            } else {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                    ?: context.filesDir
                File(dir, name).apply { writeText(prep.text) }
            }.also { log(prep, "file:${it.name}") }
        }
    }

    /**
     * QR codes for SMALL lists. Payload > ~2300 chars per code → multiple
     * sequential QRs are returned (max 5), otherwise null with a flag.
     */
    suspend fun qrCodes(prep: Prepared, sizePx: Int = 720): List<Bitmap> {
        if (prep.validationError != null) return emptyList()
        return withContext(Dispatchers.Default) {
            val maxChunk = 2_300
            val text = prep.text
            if (text.isEmpty() || text.length > maxChunk * 5) return@withContext emptyList()
            val chunks = if (text.length <= maxChunk) listOf(text) else text.chunked(maxChunk)
            val writer = QRCodeWriter()
            chunks.map { chunk ->
                val hints = mapOf(EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
                val matrix = writer.encode(chunk, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
                for (x in 0 until sizePx) {
                    for (y in 0 until sizePx) {
                        bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
                bmp
            }
        }
    }

    private suspend fun persistToCache(prep: Prepared): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "cdnhunter_export_${prep.format.name.lowercase()}_${System.currentTimeMillis()}.txt")
        file.writeText(prep.text)
        file
    }

    private suspend fun log(prep: Prepared, target: String) {
        exportLogDao.insert(
            ExportLogEntity(
                format = prep.format.name,
                createdAtMs = System.currentTimeMillis(),
                itemCount = prep.itemCount,
                detail = "$target, validated=${prep.validationError == null}",
            ),
        )
    }

    fun observeLog() = exportLogDao.observeRecent()
}
