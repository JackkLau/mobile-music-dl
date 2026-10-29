package app.tingyu.lyrics

import kotlinx.serialization.Serializable

@Serializable
data class LyricsDocument(
    val plainText: String = "",
    val syncedText: String = "",
    val instrumental: Boolean = false,
    val source: String = "",
    val sourceUrl: String = "",
    /** User calibration. Positive values show lyrics later than their original timestamp. */
    val offsetMs: Long = 0,
)

data class LyricLine(val timeMs: Long, val text: String)

/** A bounded LRC reader. Unknown tags and malformed timestamps are never shown as lyrics. */
object LrcParser {
    const val MAX_BYTES = 256 * 1024
    const val MAX_LINES = 3000
    const val MAX_OFFSET_MS = 30_000L
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("(?im)^\\s*\\[offset:([+-]?\\d{1,8})]\\s*$")
    private val leadingTags = Regex("^(?:\\[[^]\\r\\n]*])+\\s*")

    fun parse(text: String): List<LyricLine> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "歌词文件过大，请选择 256 KB 以内的文件" }
        val content = text.removePrefix("\uFEFF")
        // Positive LRC offset means the lyric clock is ahead: show that line earlier.
        val shift = offset.findAll(content).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val result = mutableListOf<LyricLine>()
        content.lineSequence().take(MAX_LINES + 1).forEachIndexed { index, raw ->
            require(index < MAX_LINES) { "歌词行数过多，请选择较短的文件" }
            val line = raw.trim()
            val tags = leadingTags.find(line)?.value ?: return@forEachIndexed
            val content = line.substring(tags.length).trim()
            timestamp.findAll(tags).forEach { match ->
                require(result.size < MAX_LINES) { "歌词时间标记过多，请选择较短的文件" }
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                if (seconds >= 60) return@forEach
                val fraction = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                result += LyricLine((minutes * 60_000 + seconds * 1000 + fraction - shift).coerceAtLeast(0), content)
            }
        }
        // A timestamp can carry original and translated lines. Merge instead of dropping either.
        return result.sortedBy { it.timeMs }.groupBy { it.timeMs }.map { (time, lines) ->
            LyricLine(time, lines.map { it.text }.distinct().joinToString("\n"))
        }
    }

    fun clampOffset(offsetMs: Long): Long = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    /** The stored line already includes any offset embedded in the LRC file. */
    fun seekPosition(line: LyricLine, offsetMs: Long = 0, durationMs: Long = Long.MAX_VALUE): Long =
        shifted(line.timeMs, clampOffset(offsetMs)).coerceIn(0, durationMs.coerceAtLeast(0))

    fun activeIndex(lines: List<LyricLine>, positionMs: Long, offsetMs: Long = 0): Int {
        val lyricPosition = shifted(positionMs, -clampOffset(offsetMs))
        var low = 0
        var high = lines.lastIndex
        var active = -1
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (lines[middle].timeMs <= lyricPosition) { active = middle; low = middle + 1 }
            else high = middle - 1
        }
        return active
    }

    private fun shifted(value: Long, delta: Long): Long = when {
        delta > 0 && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0 && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> value + delta
    }
}
