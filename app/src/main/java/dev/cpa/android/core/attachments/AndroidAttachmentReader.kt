package dev.cpa.android.core.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidAttachmentReader(context: Context) {
    private val context = context.applicationContext
    suspend fun read(uri: Uri, photo: Boolean): LocalAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = if (photo) "image.jpg" else "file"
        var declaredSize: Long? = null
        if (uri.scheme == "content") resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) declaredSize = cursor.getLong(it) }
            }
        }
        if (uri.scheme == "file") name = uri.lastPathSegment ?: name
        require((declaredSize ?: 0) <= MAX_ATTACHMENT_BYTES) { "附件超过25 MiB" }
        val id = UUID.randomUUID().toString()
        val folder = File(context.cacheDir, "composer-attachments").apply { mkdirs() }
        val file = File(folder, id)
        val thumb = File(folder, "$id.thumb.jpg")
        try {
            val input = resolver.openInputStream(uri) ?: error("无法读取附件")
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val size = source.read(buffer)
                    if (size < 0) break
                    total += size
                    require(total <= MAX_ATTACHMENT_BYTES) { "附件超过25 MiB" }
                    output.write(buffer, 0, size)
                }
            } }
            val imageFile = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
            if (photo || imageFile) {
                try { return@withContext image(file, thumb, id, name) }
                catch (e: Exception) { if (photo) throw e }
                // A corrupt/unsupported image picked as a document remains a regular file.
                thumb.delete()
            }
            LocalAttachment(id, name, resolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream", false, file.path, file.length())
        } catch (e: Exception) { file.delete(); thumb.delete(); throw e }
    }
    private fun image(file: File, thumb: File, id: String, name: String): LocalAttachment {
        val signature = file.inputStream().use { it.readNBytes(12) }
        val detected = imageMime(signature)
        var preserve = false
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width; val height = info.size.height
            require(width > 0 && height > 0)
            preserve = detected != null && width <= 2000 && height <= 2000
            val scale = minOf(1.0, (if (preserve) 360.0 else 2000.0) / max(width, height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
        }
        val converted = File(file.parentFile, "$id.converted")
        try {
            if (!preserve) converted.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            val scale = minOf(1.0, 360.0 / max(bitmap.width, bitmap.height))
            val small = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
            try { thumb.outputStream().use { require(small.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
            finally { if (small !== bitmap) small.recycle() }
            if (!preserve) check(converted.renameTo(file)) { "无法保存压缩图片" }
        } finally { bitmap.recycle(); converted.delete() }
        require(file.length() <= MAX_ATTACHMENT_BYTES)
        val outputName = if (preserve) name else name.substringBeforeLast('.').ifBlank { "image" } + ".jpg"
        return LocalAttachment(id, outputName, if (preserve) detected!! else "image/jpeg", true, file.path, file.length(), thumb.path)
    }
}

internal fun imageMime(bytes: ByteArray): String? {
    fun starts(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { bytes[it].toInt() and 255 == signature[it] }
    return when {
        starts(255, 216, 255) -> "image/jpeg"
        starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
        bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        starts(66, 77) -> "image/bmp"
        else -> null
    }
}
