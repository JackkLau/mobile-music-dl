package app.tingyu.data.local

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tingyu.lyrics.LrcParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongFilesIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val importer = SongFileImporter(context)

    @Test fun selectedLrcIsReadAndRetainsTimingWithoutPersistentDocumentAccess() {
        withDocument("text/plain", "[ti:合成测试]\n[00:01.20]第一行测试\n[00:03.40]第二行测试".toByteArray()) { uri ->
            val lyrics = importer.lyrics(uri)
            assertEquals("本地导入", lyrics.source)
            assertEquals(listOf(1200L, 3400L), LrcParser.parse(lyrics.syncedText).map { it.timeMs })
        }
    }

    @Test fun plainAndUtf16LyricsImportAndBinaryFilesAreRejected() {
        withDocument("text/plain", "普通合成歌词".toByteArray()) { assertEquals("普通合成歌词", importer.lyrics(it).plainText) }
        withDocument("text/plain", byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "另一段合成文字".toByteArray(Charsets.UTF_16LE)) {
            assertEquals("另一段合成文字", importer.lyrics(it).plainText)
        }
        withDocument("application/octet-stream", byteArrayOf(0, 1, 2, 3)) { assertTrue(runCatching { importer.lyrics(it) }.isFailure) }
        withDocument("text/plain", ByteArray(256 * 1024 + 1) { 65 }) { assertTrue(runCatching { importer.lyrics(it) }.isFailure) }
    }

    @Test fun selectedArtworkIsResizedAndOnlyOwnedCoverCanBeRemoved() {
        val bitmap = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        var cover: File? = null
        try {
            withDocument("image/png", bytes) { cover = importer.cover(it) }
            val decoded = BitmapFactory.decodeFile(cover!!.absolutePath)
            assertEquals(1024, decoded.height)
            assertTrue(decoded.width in 340..342)
            decoded.recycle()
            val unrelated = File.createTempFile("do-not-delete-", ".txt", context.cacheDir)
            try {
                importer.deleteOwnedCover(Uri.fromFile(unrelated).toString())
                assertTrue(unrelated.exists())
            } finally { unrelated.delete() }
            importer.deleteOwnedCover(Uri.fromFile(cover).toString())
            assertFalse(cover!!.exists())
        } finally { cover?.delete() }
    }

    private fun withDocument(mime: String, bytes: ByteArray, block: (Uri) -> Unit) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "tingyu-song-test-${UUID.randomUUID()}")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Test document could not be created")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            block(uri)
        } finally { resolver.delete(uri, null, null) }
    }
}
