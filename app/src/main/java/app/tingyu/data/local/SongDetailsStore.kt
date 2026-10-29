package app.tingyu.data.local

import app.tingyu.lyrics.LyricsDocument
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
)

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

    fun apply(track: Track): Track {
        val normalized = MusicMetadata.normalize(track)
        val detail = details(track.id) ?: return normalized
        return normalized.copy(title = detail.title ?: normalized.title, artist = detail.artist ?: normalized.artist,
            coverUrl = detail.coverUri ?: normalized.coverUrl)
    }

    suspend fun edit(track: Track, title: String, artist: String) {
        require(title.trim().isNotEmpty() && title.length <= 200 && artist.length <= 200) { "请填写歌名，歌名和歌手各不超过 200 字" }
        update(track.id) { old ->
            val current = apply(track)
            old.copy(title = title.trim(), artist = artist.trim(),
                lyrics = old.lyrics.takeIf { current.title == title.trim() && current.artist == artist.trim() })
        }
    }

    suspend fun saveLyrics(track: Track, lyrics: LyricsDocument): Boolean {
        var saved = false
        update(track.id) { old ->
            val current = apply(track)
            // Editing a song while a lookup/import is in flight must not attach old lyrics.
            if (current.title != track.title || current.artist != track.artist) old
            else old.copy(lyrics = lyrics).also { saved = true }
        }
        return saved
    }

    suspend fun clearLyrics(id: String) = update(id) { it.copy(lyrics = null) }
    suspend fun setCover(id: String, uri: String?) = update(id) { it.copy(coverUri = uri) }

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
