package app.tingyu.data.local

import app.tingyu.lyrics.LyricsDocument
import app.tingyu.lyrics.LrcParser
import app.tingyu.model.MusicMetadata
import app.tingyu.model.Track
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Sidecar data leaves audio files, MediaStore URIs and the existing Room database untouched. */
@Serializable
data class SongDetails(
    val id: String,
    val title: String? = null,
    val artist: String? = null,
    val coverUri: String? = null,
    val lyrics: LyricsDocument? = null,
    val lyricsIdentity: SongSignature? = null,
    val coverSource: String = "",
    val coverSourceUrl: String = "",
    val coverIdentity: SongSignature? = null,
)

/** Signature of the audio used to select lyrics; unknown legacy fields do not invent certainty. */
@Serializable
data class SongSignature(
    val title: String,
    val artist: String,
    val bvid: String,
    val cid: Long,
    val page: Int,
    val durationMs: Long,
) {
    fun matches(track: Track): Boolean = title == track.title && artist == track.artist &&
        bvid == track.bvid && page == track.page &&
        (cid <= 0 || track.cid <= 0 || cid == track.cid) &&
        (durationMs <= 0 || track.durationMs <= 0 ||
            kotlin.math.abs(durationMs.toDouble() - track.durationMs.toDouble()) <= 2000.0)

    companion object {
        fun from(track: Track): SongSignature = SongSignature(track.title, track.artist, track.bvid, track.cid, track.page, track.durationMs)
    }
}

class SongDetailsStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow<Map<String, SongDetails>>(emptyMap())
    val state = mutable.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked() {
        if (loaded) return
        val values = directory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
            runCatching {
                require(file.length() <= MAX_RECORD_BYTES)
                json.decodeFromString<SongDetails>(file.readText()).takeIf { file.name == "${key(it.id)}.json" }
            }.getOrNull()
        }
        mutable.value = values.associateBy { it.id }
        loaded = true
    }

    fun details(id: String): SongDetails? = mutable.value[id]

    fun lyricsFor(track: Track): LyricsDocument? {
        val current = apply(track)
        val detail = details(current.id) ?: return null
        // v0.1.3 records have no query signature. Keep those readable; their original
        // lookup identity cannot be reconstructed, so a fresh lookup adds provenance.
        return detail.lyrics?.takeIf { detail.lyricsIdentity?.matches(current) != false }
            ?.let { it.copy(offsetMs = LrcParser.clampOffset(it.offsetMs)) }
    }

    fun apply(track: Track): Track {
        val normalized = MusicMetadata.normalize(track)
        val detail = details(track.id) ?: return normalized
        val current = normalized.copy(title = detail.title ?: normalized.title, artist = detail.artist ?: normalized.artist)
        val signature = detail.coverIdentity
        val staleCover = detail.coverSource.isNotBlank() && signature != null &&
            (signature.title != current.title || signature.artist != current.artist)
        // A caller may already hold a decorated Track with the previous artwork URI.
        // Never fall back to that stale URI after the song identity has changed.
        return current.copy(coverUrl = if (staleCover) "" else detail.coverUri ?: current.coverUrl)
    }

    suspend fun edit(track: Track, title: String, artist: String) {
        require(title.trim().isNotEmpty() && title.length <= 200 && artist.length <= 200) { "请填写歌名，歌名和歌手各不超过 200 字" }
        update(track.id) { old ->
            val current = apply(track)
            val changed = current.title != title.trim() || current.artist != artist.trim()
            val clearMatchedCover = changed && old.coverSource.isNotBlank()
            old.copy(title = title.trim(), artist = artist.trim(),
                lyrics = old.lyrics.takeUnless { changed },
                lyricsIdentity = old.lyricsIdentity.takeUnless { changed },
                coverUri = if (clearMatchedCover) "" else old.coverUri,
                coverSource = if (clearMatchedCover) "" else old.coverSource,
                coverSourceUrl = if (clearMatchedCover) "" else old.coverSourceUrl,
                coverIdentity = old.coverIdentity.takeUnless { clearMatchedCover })
        }
    }

    suspend fun saveLyrics(track: Track, lyrics: LyricsDocument): Boolean {
        var saved = false
        update(track.id) { old ->
            val current = apply(track)
            // Editing a song while a lookup/import is in flight must not attach old lyrics.
            if (current.title != track.title || current.artist != track.artist) old
            else {
                val oldMatches = old.lyricsIdentity?.matches(current) != false
                val unchanged = oldMatches && old.lyrics?.copy(offsetMs = 0) == lyrics.copy(offsetMs = 0)
                val offset = if (unchanged) old.lyrics?.offsetMs ?: 0 else lyrics.offsetMs
                old.copy(lyrics = lyrics.copy(offsetMs = LrcParser.clampOffset(offset)),
                    lyricsIdentity = SongSignature.from(current)).also { saved = true }
            }
        }
        return saved
    }

    suspend fun adjustLyricsOffset(track: Track, deltaMs: Long): Boolean = changeLyricsOffset(track) {
        // Limiting the delta before addition avoids overflow without changing the clamped result.
        LrcParser.clampOffset(it + deltaMs.coerceIn(-2 * LrcParser.MAX_OFFSET_MS, 2 * LrcParser.MAX_OFFSET_MS))
    }

    suspend fun resetLyricsOffset(track: Track): Boolean = changeLyricsOffset(track) { 0 }

    private suspend fun changeLyricsOffset(track: Track, transform: (Long) -> Long): Boolean {
        var changed = false
        update(track.id) { old ->
            val current = apply(track)
            val lyrics = old.lyrics
            if (lyrics == null || current.title != track.title || current.artist != track.artist ||
                old.lyricsIdentity?.matches(current) == false) old
            else old.copy(lyrics = lyrics.copy(offsetMs = transform(LrcParser.clampOffset(lyrics.offsetMs))),
                lyricsIdentity = old.lyricsIdentity ?: SongSignature.from(current)).also { changed = true }
        }
        return changed
    }

    suspend fun clearLyrics(id: String) = update(id) { it.copy(lyrics = null, lyricsIdentity = null) }
    suspend fun setCover(id: String, uri: String?, source: String = "", sourceUrl: String = "") =
        update(id) { it.copy(coverUri = uri, coverSource = source, coverSourceUrl = sourceUrl, coverIdentity = null) }

    suspend fun saveMatchedCover(track: Track, uri: String, source: String, sourceUrl: String): Boolean {
        // The caller verifies/copies image bytes into its owned private artwork directory first.
        require(uri.startsWith("file://") && source.isNotBlank() && sourceUrl.startsWith("https://")) { "封面来源不受支持" }
        var saved = false
        update(track.id) { old ->
            val current = apply(track)
            if (current.title != track.title || current.artist != track.artist) old
            else old.copy(coverUri = uri, coverSource = source, coverSourceUrl = sourceUrl,
                coverIdentity = SongSignature.from(current)).also { saved = true }
        }
        return saved
    }

    private suspend fun update(id: String, transform: (SongDetails) -> SongDetails) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val old = mutable.value[id] ?: SongDetails(id)
            val next = transform(old)
            if (next == old) return@withLock
            val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_RECORD_BYTES) { "歌曲信息过大，请使用较短的歌词文件" }
            check(directory.isDirectory || directory.mkdirs()) { "无法保存歌曲信息" }
            val target = File(directory, "${key(id)}.json")
            val temporary = File.createTempFile("song-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                mutable.value = mutable.value + (id to next)
            } finally { temporary.delete() }
        }
    }

    companion object {
        private const val MAX_RECORD_BYTES = 1024 * 1024
        internal fun key(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
