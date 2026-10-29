package app.tingyu.data.bili

import app.tingyu.download.DownloadRules
import java.io.IOException
import okhttp3.Response

/** Checks the response before its bytes enter a download or a resumed local file. */
object MediaResponsePolicy {
    /** Returns the complete entity length, or -1 for a response with unknown length. */
    fun validate(response: Response, offset: Long): Long {
        require(offset >= 0)
        if (response.code !in setOf(200, 206)) {
            throw InvalidMediaResponseException("http_status", "音频服务器未返回可下载内容，请重新解析")
        }
        if (offset > 0 && response.code != 206) {
            throw InvalidMediaResponseException("range", "服务器未接受音频续传，请重新下载")
        }
        val body = response.body
            ?: throw InvalidMediaResponseException("empty_body", "音频内容为空，请重新解析")
        val lengthHeader = response.header("Content-Length")
        val declaredLength = lengthHeader?.trim()?.toLongOrNull()
        if (lengthHeader != null && (declaredLength == null || declaredLength < 0)) {
            throw InvalidMediaResponseException("length", "服务器返回了错误的音频长度，请重试")
        }
        val total = if (response.code == 206) {
            val range = DownloadRules.parseContentRange(response.header("Content-Range"))
            if (range == null || range.start != offset) {
                throw InvalidMediaResponseException("range", "服务器返回了错误的音频分段，请重试")
            }
            if (declaredLength != null && declaredLength != range.end - range.start + 1) {
                throw InvalidMediaResponseException("length", "音频分段与响应长度不一致，请重试")
            }
            range.total
        } else declaredLength ?: body.contentLength()

        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
        if (type.startsWith("text/") || type.contains("json") || type.contains("html") || type.contains("xml")) {
            throw InvalidMediaResponseException("content_type", "服务器返回了错误页面，未提供音频，请重新解析")
        }
        if (offset == 0L) {
            // Peek without consuming the real transfer source. Network read failures keep
            // their original exception types so the caller can diagnose timeouts and I/O.
            val preview = ByteArray(32)
            var size = 0
            body.source().peek().use { source ->
                while (size < preview.size) {
                    val count = source.read(preview, size, preview.size - size)
                    if (count == -1) break
                    size += count
                }
            }
            if (!MediaRequestPolicy.containsMp4Header(preview.copyOf(size))) {
                throw InvalidMediaResponseException("content_header", "服务器未返回有效的 MP4 音频内容，请重新解析")
            }
        }
        return total
    }
}

/** Both fields are app-owned; callers must never insert response bodies or URLs. */
class InvalidMediaResponseException(val reason: String, message: String) : IOException(message)
