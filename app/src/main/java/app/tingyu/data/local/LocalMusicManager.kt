package app.tingyu.data.local

import android.Manifest
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.tingyu.model.LocalTrack
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanResult(val added: Int, val existing: Int, val skipped: Int, val failed: Int, val permissionRequired: Boolean)
data class ImportResult(val added: Int, val existing: Int, val failed: Int)
sealed interface DeleteResult {
    data class Deleted(val alreadyMissing: Boolean = false) : DeleteResult
    data class RequiresConfirmation(val request: PendingIntent) : DeleteResult
    data class Failed(val message: String) : DeleteResult
}

/** The Activity supplies only the read/write grant bits actually returned by the picker. */
internal fun interface PersistedAudioGrant {
    fun acquire(uri: Uri, flags: Int): Boolean
}

/** Local discovery never removes an invisible entry and never copies or transcodes a file. */
class LocalMusicManager internal constructor(
    private val context: Context,
    private val library: LibraryStore,
    private val grant: PersistedAudioGrant,
) {
    constructor(context: Context, library: LibraryStore) : this(context.applicationContext, library,
        PersistedAudioGrant { uri, flags ->
            val resolver = context.applicationContext.contentResolver
            val allowed = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (allowed and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) false else {
                resolver.takePersistableUriPermission(uri, allowed)
                resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            }
        })

    private val resolver get() = context.contentResolver

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        var added = 0
        var existing = 0
        var skipped = 0
        var failed = 0
        var permissionRequired = !canReadOtherAudio()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATE_ADDED)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0" +
            " AND ${MediaStore.Audio.Media.SIZE} > 0 AND ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'" +
            if (Build.VERSION.SDK_INT >= 30) " AND ${MediaStore.Audio.Media.IS_TRASHED} = 0" else ""
        try {
            val cursor = resolver.query(collection, projection, selection, arrayOf(MUSIC_DIRECTORY), null)
                ?: throw IOException("音乐目录暂时不可用")
            cursor.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val volume = it.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), it.getLong(0))
                    try {
                        val metadata = inspectAudio(uri)
                        val track = LocalMusicRules.track(it.getString(2).orEmpty(), uri.toString(),
                            it.getString(3).orEmpty(), it.getString(4).orEmpty(), metadata.durationMs)
                        val savedAt = it.getLong(5).takeIf { seconds -> seconds > 0 }?.times(1000) ?: System.currentTimeMillis()
                        if (library.addDiscovered(LocalTrack(track, uri.toString(), savedAt, identityUri = uri.toString()))) added++ else existing++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: InvalidAudio) { skipped++ }
                    catch (_: SecurityException) { failed++; permissionRequired = true }
                    catch (_: Exception) { failed++ }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { failed++; permissionRequired = true }
        catch (_: Exception) { failed++ }
        ScanResult(added, existing, skipped, failed, permissionRequired)
    }

    suspend fun importUris(uris: List<Uri>, persistableFlags: Int): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var existing = 0
        var failed = 0
        for (uri in uris.distinctBy { LocalMusicRules.uriKey(it.toString()) }) {
            currentCoroutineContext().ensureActive()
            try {
                require(uri.scheme == "content")
                val flags = persistableFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 || !grant.acquire(uri, flags)) {
                    throw SecurityException("无法保留音频读取授权")
                }
                val metadata = inspectAudio(uri)
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty()
                val track = LocalMusicRules.track(name, uri.toString(), metadata.title, metadata.artist, metadata.durationMs)
                if (library.addDiscovered(LocalTrack(track, uri.toString(), identityUri = mediaIdentity(uri)))) added++ else existing++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++ }
        }
        ImportResult(added, existing, failed)
    }

    /** Called only after the app's delete confirmation. System consent is a separate step. */
    suspend fun delete(local: LocalTrack, afterUserApproval: Boolean = false): DeleteResult = withContext(Dispatchers.IO) {
        val stored = library.saved(local.track.id)
        if (stored?.uri != local.uri) return@withContext DeleteResult.Failed("音乐记录已变化，请刷新后重试")
        val uri = Uri.parse(local.uri)
        if (uri.scheme != "content") return@withContext DeleteResult.Failed("此音频来源不支持在应用内删除")
        val document = DocumentsContract.isDocumentUri(context, uri)
        val media = uri.authority == MediaStore.AUTHORITY && uri.pathSegments.let {
            it.size == 4 && it[1] == "audio" && it[2] == "media" && (it[3].toLongOrNull() ?: 0) > 0
        }
        if (!document && !media) return@withContext DeleteResult.Failed("文件提供方不支持删除，请使用系统文件管理器")
        val systemDeleted = afterUserApproval && media && Build.VERSION.SDK_INT >= 30
        if (presence(uri, systemDeleted) == Presence.MISSING) {
            return@withContext finishDeletion(local, alreadyMissing = true)
        }
        try {
            val removed = if (document) DocumentsContract.deleteDocument(resolver, uri) else resolver.delete(uri, null, null) > 0
            if (removed || presence(uri) == Presence.MISSING) {
                finishDeletion(local)
            } else DeleteResult.Failed("文件未能删除，音乐记录已保留")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: SecurityException) {
            if (afterUserApproval) return@withContext DeleteResult.Failed("系统未允许删除此文件，音乐记录已保留")
            try {
                when {
                    media && Build.VERSION.SDK_INT >= 30 -> DeleteResult.RequiresConfirmation(MediaStore.createDeleteRequest(resolver, listOf(uri)))
                    error is RecoverableSecurityException -> DeleteResult.RequiresConfirmation(error.userAction.actionIntent)
                    else -> DeleteResult.Failed("没有删除此音频的权限，音乐记录已保留")
                }
            } catch (_: Exception) { DeleteResult.Failed("无法请求删除授权，音乐记录已保留") }
        } catch (_: Exception) { DeleteResult.Failed("文件未能删除，音乐记录已保留") }
    }

    private suspend fun finishDeletion(local: LocalTrack, alreadyMissing: Boolean = false): DeleteResult =
        if (library.removeLocalIfUri(local.track.id, local.uri) || library.saved(local.track.id) == null) {
            DeleteResult.Deleted(alreadyMissing)
        } else DeleteResult.Failed("旧文件已删除；音乐记录已更新，已保留新记录")

    private enum class Presence { PRESENT, MISSING, UNKNOWN }

    /** Access denial, null cursors and provider failures never count as an absent file. */
    private fun presence(uri: Uri, systemDeleted: Boolean = false): Presence = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) Presence.PRESENT
            else if (systemDeleted || uri.authority == MediaStore.AUTHORITY && canReadOtherAudio() || context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) Presence.MISSING
            else Presence.UNKNOWN
        } ?: Presence.UNKNOWN
    } catch (_: Exception) { Presence.UNKNOWN }

    private data class Metadata(val title: String, val artist: String, val durationMs: Long)
    private class InvalidAudio : IOException()

    /** Preserve the granted document URI for playback; use its MediaStore identity only for deduplication. */
    private fun mediaIdentity(uri: Uri): String? = try {
        val media = if (uri.authority == MediaStore.AUTHORITY) uri else MediaStore.getMediaUri(context, uri)
        if (media?.authority != MediaStore.AUTHORITY) null else {
            // ExternalStorageProvider can return MediaStore.Files rather than an
            // Audio URI. The shared row id and volume identify its Audio counterpart.
            resolver.query(media, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.VOLUME_NAME, MediaStore.Audio.Media.MIME_TYPE), null, null, null)?.use {
                if (!it.moveToFirst()) null else {
                    val volume = it.getString(1)
                    if (volume.isNullOrBlank() || !it.getString(2).orEmpty().startsWith("audio/")) null
                    else ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), it.getLong(0)).toString()
                }
            }
        }
    } catch (_: Exception) { null }

    private fun inspectAudio(uri: Uri): Metadata {
        val type = resolver.getType(uri).orEmpty().substringBefore(';')
        if (type.isNotEmpty() && !type.startsWith("audio/") && type != "application/octet-stream") throw InvalidAudio()
        resolver.openInputStream(uri)?.use { if (it.read() < 0) throw InvalidAudio() } ?: throw InvalidAudio()
        var durationMs = 0L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val audio = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: throw InvalidAudio()
            // User-selected audio is played as-is. This does not add a video importer.
            if (formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) throw InvalidAudio()
            if (audio.containsKey(MediaFormat.KEY_DURATION)) durationMs = audio.getLong(MediaFormat.KEY_DURATION) / 1000
        } finally { extractor.release() }
        var title = ""
        var artist = ""
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            if (durationMs <= 0) durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: Exception) { /* Valid audio can lack optional display metadata. */ }
        finally { retriever.release() }
        return Metadata(title, artist, durationMs)
    }

    private fun canReadOtherAudio(): Boolean = context.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    companion object { const val MUSIC_DIRECTORY = "Music/听屿/" }
}
