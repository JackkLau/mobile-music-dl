package app.tingyu.sharing

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import app.tingyu.data.music.MusicShareLink
import app.tingyu.model.LocalTrack
import app.tingyu.model.MusicSource
import app.tingyu.model.Track
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WECHAT = "com.tencent.mm"
private val biliId = Regex("BV[0-9A-Za-z]{10}")
private val neteaseId = Regex("netease-([1-9][0-9]{0,17})")

internal fun publicSongPage(track: Track): String? {
    if (track.localOnly) return null
    return when (track.source) {
        MusicSource.BILIBILI -> if (biliId.matches(track.bvid) && track.page > 0)
            "https://www.bilibili.com/video/${track.bvid}?p=${track.page}" else null
        MusicSource.NETEASE -> neteaseId.matchEntire(track.bvid)?.groupValues?.get(1)?.let {
            "https://music.163.com/song?id=$it"
        }
        MusicSource.QQ -> track.bvid.takeIf { it.startsWith("qq-") }?.removePrefix("qq-")
            ?.takeIf(MusicShareLink.qqMid::matches)?.let { "https://y.qq.com/n/ryqq/songDetail/$it" }
    }
}

/** Opens a chooser only when the caller explicitly launches the returned intent. */
internal suspend fun musicShareIntent(context: Context, track: Track, local: LocalTrack? = null): Intent =
    withContext(Dispatchers.IO) {
        val send = Intent(Intent.ACTION_SEND)
        val title = listOf(track.title, track.artist).filter(String::isNotBlank).joinToString(" · ")
        send.putExtra(Intent.EXTRA_TITLE, title)
        if (local != null) {
            if (local.track.id != track.id) throw IOException("歌曲已变更，请重新选择")
            val uri = Uri.parse(local.uri)
            send.type = readableAudioType(context, uri)
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData(title, arrayOf(requireNotNull(send.type)), ClipData.Item(uri))
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            val page = publicSongPage(track) ?: throw IOException("没有可分享的歌曲链接，请先导入音频文件")
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, listOf(title, page).filter(String::isNotBlank).joinToString("\n"))
        }
        Intent.createChooser(send, "分享歌曲").apply {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let { self ->
                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(self))
            }
            if (local != null) addWeChatFileTargetIfNeeded(context, send)
        }
    }

private fun readableAudioType(context: Context, uri: Uri): String {
    val error = "无法读取这首歌，请重新选择音频文件"
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank() ||
        DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) throw IOException(error)
    val resolver = context.contentResolver
    try {
        val type = resolver.getType(uri)?.substringBefore(';')?.lowercase()
        if (!type.isNullOrBlank() && !type.startsWith("audio/") && type != "application/octet-stream") throw IOException(error)
        resolver.openInputStream(uri)?.use { if (it.read() == -1) throw IOException(error) } ?: throw IOException(error)
        return type?.takeIf { it.startsWith("audio/") } ?: "audio/*"
    } catch (failure: SecurityException) {
        throw IOException(error, failure)
    } catch (failure: IllegalArgumentException) {
        throw IOException(error, failure)
    } catch (failure: IOException) {
        throw IOException(error, failure)
    }
}

@Suppress("DEPRECATION")
private fun Intent.addWeChatFileTargetIfNeeded(context: Context, audio: Intent) {
    // Some receivers advertise generic file attachments separately from typed audio.
    // Discover exported targets from the installed client, including its localized names.
    val weChat = Intent(audio).setPackage(WECHAT)
    val pm = context.packageManager
    val flags = PackageManager.MATCH_DEFAULT_ONLY
    if (pm.queryIntentActivities(weChat, flags).any { it.activityInfo.exported }) return
    weChat.type = "application/octet-stream"
    val files = pm.queryIntentActivities(weChat, flags).map { it.activityInfo }.filter { it.exported }
        .take(2).map { activity -> Intent(weChat).setClassName(activity.packageName, activity.name) }
    if (files.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, files.toTypedArray())
}
