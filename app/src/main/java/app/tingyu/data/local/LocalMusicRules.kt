package app.tingyu.data.local

import app.tingyu.model.Track
import java.security.MessageDigest
import java.util.Locale

/** Stable identities for local files; no pathname is treated as permission to read it. */
object LocalMusicRules {
    private val exportedName = Regex("^(.*)-(BV[0-9A-Za-z]{10})-P([1-9][0-9]*)(?: \\([0-9]+\\))?\\.m4a$", RegexOption.IGNORE_CASE)

    fun uriKey(uri: String): String {
        val colon = uri.indexOf(':')
        return if (colon > 0) uri.substring(0, colon).lowercase(Locale.ROOT) + uri.substring(colon) else uri
    }

    fun track(displayName: String, uri: String, title: String = "", artist: String = "", durationMs: Long = 0): Track {
        val match = exportedName.matchEntire(displayName)
        val page = match?.groupValues?.get(3)?.toIntOrNull()
        val recovered = match != null && page != null && page > 0
        val fallbackTitle = if (recovered) match!!.groupValues[1] else displayName.substringBeforeLast('.', displayName)
        return Track(
            bvid = if (recovered) match!!.groupValues[2].replaceFirst(Regex("^bv", RegexOption.IGNORE_CASE), "BV") else localBvid(uri),
            cid = 0,
            title = title.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallbackTitle.ifBlank { "本地音频" },
            artist = artist.takeUnless { it == "<unknown>" }.orEmpty(),
            durationMs = durationMs.coerceAtLeast(0),
            page = if (recovered) page!! else 1,
            sourceUrl = "",
            localOnly = true,
        )
    }

    fun withLocalIdentity(track: Track, uri: String): Track =
        track.copy(bvid = localBvid(uri), cid = 0, page = 1, sourceUrl = "", localOnly = true)

    private fun localBvid(uri: String): String = "local_" + MessageDigest.getInstance("SHA-256")
        .digest(uriKey(uri).toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
}
