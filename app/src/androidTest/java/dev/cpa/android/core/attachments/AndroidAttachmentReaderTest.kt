package dev.cpa.android.core.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class AndroidAttachmentReaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(width: Int, height: Int, suffix: String = ".png"): File {
        val file = File.createTempFile("reader-", suffix, context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); return file
    }
    @Test fun smallSupportedImageKeepsBytesAndCreatesBoundedThumbnail() = runBlocking {
        val source = png(500, 300)
        try {
            val result = AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = true)
            try {
                assertTrue(result.image); assertEquals("image/png", result.mimeType); assertEquals(source.name, result.name)
                assertArrayEquals(source.readBytes(), result.checkedFile().readBytes())
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(result.thumbnail, bounds)
                assertTrue(bounds.outWidth <= 360 && bounds.outHeight <= 360)
            } finally { result.deleteOwnedFiles() }
        } finally { source.delete() }
    }
    @Test fun oversizedDimensionsBecomeJpegWithin2000Pixels() = runBlocking {
        val source = png(2400, 1200)
        try {
            val result = AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = true)
            try {
                assertEquals("image/jpeg", result.mimeType)
                assertEquals(source.nameWithoutExtension + ".jpg", result.name)
                assertEquals("image/jpeg", imageMime(result.checkedFile().inputStream().use { it.readNBytes(12) }))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(result.path, bounds)
                assertEquals(2000, bounds.outWidth); assertEquals(1000, bounds.outHeight)
            } finally { result.deleteOwnedFiles() }
        } finally { source.delete() }
    }
    @Test fun corruptImageDocumentFallsBackButPhotoFails() = runBlocking {
        val source = File.createTempFile("bad-image", ".png", context.cacheDir).apply { writeText("not an image") }
        try {
            val result = AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = false)
            try { assertFalse(result.image); assertArrayEquals(source.readBytes(), result.checkedFile().readBytes()) }
            finally { result.deleteOwnedFiles() }
            assertTrue(runCatching { AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = true) }.isFailure)
        } finally { source.delete() }
    }
    @Test fun heicFileNameIsAFileButPhotoUsesDecodedImageFormat() = runBlocking {
        val source = png(40, 30, ".heic") // Content sniffing vs filename classification, not an HEIC codec fixture.
        try {
            val file = AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = false)
            val photo = AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = true)
            try { assertFalse(file.image); assertTrue(photo.image); assertEquals("image/png", photo.mimeType) }
            finally { file.deleteOwnedFiles(); photo.deleteOwnedFiles() }
        } finally { source.delete() }
    }
    @Test fun byteLimitIsInclusiveAndLargerInputLeavesNoPartialAttachment() = runBlocking {
        val source = File.createTempFile("reader-limit", ".bin", context.cacheDir)
        try {
            RandomAccessFile(source, "rw").use { it.setLength(MAX_ATTACHMENT_BYTES) }
            val accepted = AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = false)
            assertEquals(MAX_ATTACHMENT_BYTES, accepted.size); accepted.deleteOwnedFiles()
            val folder = File(context.cacheDir, "composer-attachments")
            val before = folder.listFiles().orEmpty().map { it.name }.toSet()
            RandomAccessFile(source, "rw").use { it.setLength(MAX_ATTACHMENT_BYTES + 1) }
            assertTrue(runCatching { AndroidAttachmentReader(context).read(Uri.fromFile(source), photo = false) }.isFailure)
            assertEquals(before, folder.listFiles().orEmpty().map { it.name }.toSet())
        } finally { source.delete() }
    }
    @Test fun cameraProviderExposesOnlyCameraDirectory() {
        val camera = File(context.cacheDir, "composer-camera/test.jpg").apply { parentFile!!.mkdirs(); writeText("test") }
        val private = File(context.cacheDir, "composer-attachments/private").apply { parentFile!!.mkdirs(); writeText("test") }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", camera)
            assertEquals("content", uri.scheme)
            assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.attachments", private) }.isFailure)
        } finally { camera.delete(); private.delete() }
    }
}
