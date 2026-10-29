package app.tingyu.data.bili

import java.net.URLEncoder
import java.security.MessageDigest

/** Public web API request signing, isolated so platform changes do not touch playback. */
internal object WbiSigner {
    private val permutation = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    fun mixinKey(imageUrl: String, subUrl: String): String {
        val source = imageUrl.substringAfterLast('/').substringBefore('.') + subUrl.substringAfterLast('/').substringBefore('.')
        if (source.length < 64) throw BiliException("B 站解析参数已变化，请稍后重试或更新应用")
        return permutation.take(32).map { source[it] }.joinToString("")
    }

    fun sign(parameters: Map<String, String>, key: String, seconds: Long): Map<String, String> {
        val clean = (parameters + ("wts" to seconds.toString())).toSortedMap()
            .mapValues { (_, value) -> value.filterNot { it in "!'()*" } }
        val query = clean.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val digest = MessageDigest.getInstance("MD5").digest((query + key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return clean + ("w_rid" to digest)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
