package app.tingyu.lyrics

import app.tingyu.model.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Providers are independent: one unavailable service does not discard another valid result. */
class LyricsLookup(
    private val primary: suspend (Track) -> LyricsDocument?,
    private val fallback: suspend (Track) -> LyricsDocument?,
) {
    suspend fun find(track: Track): LyricsDocument? {
        if (track.title.isBlank() || track.artist.isBlank()) throw IOException("请先补全歌名和歌手，再查找歌词")
        var failed = false
        for (provider in listOf(primary, fallback)) {
            try { provider(track)?.let { return it } }
            catch (error: CancellationException) { throw error }
            catch (_: IOException) { failed = true }
        }
        if (failed) throw IOException("部分歌词来源暂时不可用，其他来源未找到匹配。可稍后重试，或导入本地歌词。")
        return null
    }
}
