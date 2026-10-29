package app.tingyu.lyrics

import app.tingyu.catalog.LyricCandidate
import app.tingyu.model.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Only messages constructed by the provider are safe to show; arbitrary IO messages stay private. */
class LyricsSourceException(
    source: String,
    stage: String,
    reason: String,
    httpStatus: Int? = null,
    businessCode: Long? = null,
) : IOException(buildString {
    append(source).append(" · ").append(stage).append("：").append(reason)
    httpStatus?.let { append("（HTTP ").append(it).append("）") }
    businessCode?.let { append("（业务码 ").append(it).append("）") }
})

data class LyricsSearchResult(val candidates: List<LyricCandidate>, val warnings: List<String> = emptyList())

/** Providers are independent: one unavailable service does not discard another valid result. */
class LyricsLookup(
    private val primary: suspend (Track) -> LyricsDocument?,
    private val fallback: suspend (Track) -> LyricsDocument?,
    private val primaryCandidates: suspend (Track) -> List<LyricCandidate> = { emptyList() },
    private val fallbackCandidates: suspend (Track) -> List<LyricCandidate> = { emptyList() },
    private val primarySelected: suspend (LyricCandidate) -> LyricsDocument? = { null },
    private val fallbackSelected: suspend (LyricCandidate) -> LyricsDocument? = { null },
) {
    suspend fun find(track: Track): LyricsDocument? {
        if (track.title.isBlank() || track.artist.isBlank()) throw IOException("请先补全歌名和歌手，再查找歌词")
        val failures = mutableListOf<String>()
        for ((source, provider) in listOf("网易云音乐" to primary, "LRCLIB" to fallback)) {
            try { provider(track)?.let { return it } }
            catch (error: CancellationException) { throw error }
            catch (error: IOException) { failures += safeFailure(source, error) }
        }
        if (failures.isNotEmpty()) throw IOException(failures.joinToString("\n") + "\n可选择歌词版本，或导入本地歌词。")
        return null
    }

    suspend fun candidates(track: Track): LyricsSearchResult {
        if (track.title.isBlank()) throw IOException("请先填写歌名，再查找歌词")
        if (track.title.length > 200 || track.artist.length > 200) throw IOException("歌名或歌手过长，请缩短搜索词")
        val result = mutableListOf<LyricCandidate>()
        val failures = mutableListOf<String>()
        for ((source, provider) in listOf("网易云音乐" to primaryCandidates, "LRCLIB" to fallbackCandidates)) {
            try { result += provider(track) }
            catch (error: CancellationException) { throw error }
            catch (error: IOException) { failures += safeFailure(source, error) }
        }
        // Distinct IDs can represent different recordings, even with identical names and durations.
        // Keep them visible; do not silently merge or attach one on the user's behalf.
        return LyricsSearchResult(result.distinctBy { it.id }, failures)
    }

    suspend fun select(candidate: LyricCandidate): LyricsDocument? = when {
        candidate.id.startsWith("netease:song:") -> primarySelected(candidate)
        candidate.id.startsWith("lrclib:song:") -> fallbackSelected(candidate)
        else -> throw IOException("歌词来源不可用，请重新查找")
    }

    private fun safeFailure(source: String, error: IOException): String =
        if (error is LyricsSourceException) error.message.orEmpty() else "$source 暂时不可用，请稍后重试"
}
