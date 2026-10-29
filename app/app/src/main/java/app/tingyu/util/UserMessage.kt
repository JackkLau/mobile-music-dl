package app.tingyu.util

/** Only concise, app-owned Chinese explanations may cross into product UI. */
fun userMessage(error: Throwable, fallback: String): String {
    var current: Throwable? = error
    repeat(8) {
        val value = current ?: return fallback
        val message = value.message.orEmpty()
        val sensitive = listOf("http://", "https://", "cookie", "sessdata", "token=", "authorization", "bili_jct")
            .any { message.contains(it, ignoreCase = true) }
        if (!sensitive && message.length in 1..200 && message.any { it in '\u4e00'..'\u9fff' }) return message.replace('\n', ' ')
        current = value.cause?.takeUnless { it === value }
    }
    return fallback
}
