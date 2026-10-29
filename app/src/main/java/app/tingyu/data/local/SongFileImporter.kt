package app.tingyu.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.tingyu.lyrics.LrcParser
import app.tingyu.lyrics.LyricsDocument
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Copies selected artwork/lyrics into private storage; no long-lived access to user's documents. */
class SongFileImporter(private val context: Context) {
    fun lyrics(uri: Uri): LyricsDocument {
        val bytes = readBounded(uri, 256 * 1024)
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().trimStart('\uFEFF').trim() }
        catch (_: Exception) { throw IOException("歌词编码无法读取，请将文件保存为 UTF-8 后重试") }
        if (text.isBlank() || text.any { it == '\u0000' } || text.startsWith("<")) throw IOException("请选择包含文字的 LRC 或 TXT 歌词文件")
        if (text.lines().size > 3000) throw IOException("歌词行数过多，请选择单首歌曲的歌词")
        val lines = LrcParser.parse(text)
        return if (lines.any { it.text.isNotBlank() }) LyricsDocument(syncedText = text, source = "本地导入")
        else LyricsDocument(plainText = text, source = "本地导入")
    }

    fun cover(uri: Uri): File {
        return cover(readBounded(uri, 12 * 1024 * 1024))
    }

    fun cover(bytes: ByteArray): File {
        if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) throw IOException("封面文件无法读取或过大")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val scale = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val directory = File(context.filesDir, "song-covers").apply { mkdirs() }
        val file = File.createTempFile("cover-", ".jpg", directory)
        try {
            file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("封面保存失败") }
            return file
        } catch (error: Exception) { file.delete(); throw error }
        finally { bitmap.recycle() }
    }

    fun deleteOwnedCover(uri: String?) {
        if (uri == null) return
        runCatching {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "file") {
                val file = File(parsed.path ?: return)
                if (file.canonicalFile.parentFile == File(context.filesDir, "song-covers").canonicalFile) file.delete()
            }
        }
    }

    private fun readBounded(uri: Uri, maximum: Int): ByteArray {
        if (uri.scheme != "content") throw IOException("请选择系统文件选择器中的文件")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("文件无法读取，请重新选择")
        return input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() + count > maximum) throw IOException("文件过大，请选择较小的歌词或封面")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
