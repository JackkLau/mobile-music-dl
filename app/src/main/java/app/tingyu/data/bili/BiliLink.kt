package app.tingyu.data.bili

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

sealed interface BiliLink {
    data class Video(val bvid: String? = null, val aid: Long? = null, val page: Int? = null) : BiliLink
    data class Favorite(val id: Long) : BiliLink
    data class Short(val url: HttpUrl) : BiliLink
}

/** Deliberately accepts only known public Bilibili link shapes, never arbitrary URLs. */
object BiliLinkParser {
    private val bv = Regex("BV[0-9A-Za-z]{10}")
    private val av = Regex("av([1-9][0-9]*)", RegexOption.IGNORE_CASE)
    private val urlStart = Regex("https?://", RegexOption.IGNORE_CASE)
    // Copy/share controls sometimes insert these without a visible separator. Encoded
    // values such as %E2%80%8B are deliberately untouched.
    private val invisible = Regex("[\u200B\u200C\u200D\u2060\uFEFF]")
    private val videoHosts = setOf("bilibili.com", "www.bilibili.com", "m.bilibili.com", "space.bilibili.com")
    private val shortHosts = setOf("b23.tv", "www.b23.tv")

    fun parse(input: String): BiliLink {
        val text = input.replace(invisible, "").trim()
        if (text.isEmpty()) throw BiliException("请粘贴 B 站视频或收藏夹链接")
        if (bv.matches(text)) return BiliLink.Video(bvid = text)
        av.matchEntire(text)?.let { return BiliLink.Video(aid = positive(it.groupValues[1])) }
        val candidates = urlStart.findAll(text).map { extractUrl(text, it.range.first) }.toList()
        // A share description may contain a preceding unrelated URL. Select a known
        // Bilibili host, then apply the normal credential and port validation below.
        val raw = candidates.firstOrNull { it.toHttpUrlOrNull()?.host in videoHosts + shortHosts }
            ?: candidates.firstOrNull()
            ?: throw BiliException("未找到 B 站链接，请复制视频或收藏夹的分享链接")
        val original = raw.toHttpUrlOrNull() ?: throw BiliException("链接格式不正确")
        if (original.username.isNotEmpty() || original.password.isNotEmpty()) throw BiliException("不支持包含账号信息的链接")
        if (original.port != if (original.scheme == "https") 443 else 80) throw BiliException("不支持此链接端口")
        val url = original.newBuilder().scheme("https").port(443).build()
        if (url.host in shortHosts) return BiliLink.Short(url)
        if (url.host !in videoHosts) throw BiliException("请使用 bilibili.com 或 b23.tv 的分享链接")

        val parts = url.pathSegments.filter(String::isNotEmpty)
        if (parts.firstOrNull() == "video" && parts.size >= 2) {
            val id = parts[1]
            val page = url.queryParameter("p")?.let {
                it.toIntOrNull()?.takeIf { value -> value > 0 } ?: throw BiliException("分 P 编号不正确")
            }
            if (bv.matches(id)) return BiliLink.Video(bvid = id, page = page)
            av.matchEntire(id)?.let { return BiliLink.Video(aid = positive(it.groupValues[1]), page = page) }
        }
        if (url.host == "space.bilibili.com" && parts.any { it == "favlist" || it == "favlistdetail" }) {
            return BiliLink.Favorite(positive(url.queryParameter("fid") ?: throw BiliException("收藏夹链接缺少 fid，请复制具体收藏夹的链接")))
        }
        if (parts.firstOrNull() == "list" && parts.getOrNull(1)?.startsWith("ml") == true) {
            return BiliLink.Favorite(positive(parts[1].removePrefix("ml")))
        }
        if (parts.firstOrNull() == "medialist" && parts.getOrNull(1) == "detail" && parts.getOrNull(2)?.startsWith("ml") == true) {
            return BiliLink.Favorite(positive(parts[2].removePrefix("ml")))
        }
        throw BiliException("暂不支持此类 B 站链接，请使用普通视频或具体收藏夹链接")
    }

    private fun extractUrl(text: String, start: Int): String {
        val quote = text.getOrNull(start - 1)?.takeIf { it == '\'' }
        val balance = mutableMapOf(')' to 0, ']' to 0, '}' to 0)
        val closing = mapOf('(' to ')', '[' to ']', '{' to '}')
        var end = start
        while (end < text.length) {
            val c = text[end]
            if (c.isWhitespace() || Character.isSpaceChar(c) ||
                c in "<>\"`“”‘’「」『』【】（）《》〈〉。，；！？" || c == quote) break
            closing[c]?.let { balance[it] = balance.getValue(it) + 1 }
            if (c in balance) {
                if (balance.getValue(c) == 0) break
                balance[c] = balance.getValue(c) - 1
            }
            end++
        }
        var raw = text.substring(start, end)
        // Markdown escapes ampersands in pasted link destinations. Don't decode
        // percent escapes or recompose the URL, especially its query values.
        raw = raw.replace("\\&", "&")
        // Sentence punctuation after a path, or a numeric identity parameter, is
        // wrapping text. Keep punctuation inside arbitrary query/fragment values.
        if ('?' !in raw && '#' !in raw || Regex("[?&](?:p|fid)=[0-9]+[.,;!]+$").containsMatchIn(raw)) {
            raw = raw.trimEnd('.', ',', ';', '!')
        }
        return raw
    }

    fun isSafeRedirect(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && (url.host in videoHosts || url.host in shortHosts)

    private fun positive(value: String): Long = value.toLongOrNull()?.takeIf { it > 0 }
        ?: throw BiliException("链接中的编号不正确")
}

/** Messages are app-owned and safe for display; never include remote JSON or request URLs. */
class BiliException(
    message: String,
    val code: Int? = null,
    val stage: String? = null,
    val httpStatus: Int? = null,
    val connection: ConnectionMetadata? = null,
) : IOException(message)
