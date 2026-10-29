package app.tingyu.download

import app.tingyu.data.bili.MediaUrlPolicy

/** Pure rules shared by the downloader and its contract tests. */
object DownloadRules {
    data class ContentRange(val start: Long, val end: Long, val total: Long)

    fun httpMessage(status: Int): String = when (status) {
        401 -> "音频服务器要求有效授权（HTTP 401），请检查账号后重新解析"
        403 -> "音频服务器拒绝访问（HTTP 403），可能与来源校验、账号权限或平台限制有关，请稍后重试"
        404 -> "音频地址不存在（HTTP 404），请重新解析"
        410 -> "音频地址已过期（HTTP 410），请重新解析"
        412, 429 -> "音频服务器暂时限制请求（HTTP $status），请稍后重试"
        else -> "音频下载未完成（HTTP $status），请稍后重试"
    }

    fun parseContentRange(value: String?): ContentRange? {
        val groups = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(value.orEmpty())?.groupValues ?: return null
        val start = groups[1].toLongOrNull() ?: return null
        val end = groups[2].toLongOrNull() ?: return null
        val total = groups[3].toLongOrNull() ?: return null
        return if (start <= end && end < total) ContentRange(start, end, total) else null
    }

    fun strongEtag(value: String?): String? = value?.trim()?.takeIf {
        it.startsWith('"') && it.endsWith('"') && it.length > 2 && !it.startsWith("W/")
    }

    fun canAppend(
        responseCode: Int,
        localBytes: Long,
        expectedTotal: Long,
        expectedEtag: String?,
        actualEtag: String?,
        contentRange: String?,
    ): Boolean {
        val range = parseContentRange(contentRange) ?: return false
        return responseCode == 206 && localBytes > 0 && range.start == localBytes &&
            expectedTotal > localBytes && range.total == expectedTotal &&
            strongEtag(expectedEtag) != null && strongEtag(expectedEtag) == strongEtag(actualEtag)
    }

    fun fileName(title: String, bvid: String, page: Int): String {
        val clean = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim().trim('.').take(70).ifBlank { "音频" }
        val identity = bvid.replace(Regex("[^A-Za-z0-9]"), "").take(32)
        return "$clean-$identity-P$page.m4a"
    }

    fun permittedMediaUrl(value: String): Boolean = MediaUrlPolicy.permitted(value)

    fun expectedLength(responseCode: Int, contentLength: Long, contentRange: String?): Long =
        if (responseCode == 206) parseContentRange(contentRange)?.total ?: -1 else contentLength

    fun complete(expectedBytes: Long, actualBytes: Long): Boolean =
        actualBytes > 0 && (expectedBytes < 0 || expectedBytes == actualBytes)
}
