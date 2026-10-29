package app.tingyu.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.tingyu.data.bili.BilibiliRepository
import app.tingyu.data.bili.ConnectionMetadata
import app.tingyu.data.bili.MediaRequestPolicy
import app.tingyu.data.bili.MediaResponsePolicy
import app.tingyu.data.bili.InvalidMediaResponseException
import app.tingyu.data.bili.NetworkConnectionListener
import app.tingyu.data.bili.NetworkProfile
import app.tingyu.data.bili.NetworkTrace
import app.tingyu.data.local.LibraryStore
import app.tingyu.model.AudioStream
import app.tingyu.model.DownloadStatus
import app.tingyu.model.DownloadTask
import app.tingyu.model.LocalTrack
import app.tingyu.model.Track
import app.tingyu.util.userMessage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

/** Downloads are durable user actions. Service death never silently re-enqueues old work. */
class DownloadCoordinator(
    context: Context,
    private val bilibili: BilibiliRepository,
    private val library: LibraryStore,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val runnerMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val interrupted = ConcurrentHashMap.newKeySet<String>()
    private val _jobs = MutableStateFlow<List<DownloadTask>>(emptyList())
    val jobs: StateFlow<List<DownloadTask>> = _jobs.asStateFlow()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .eventListenerFactory { NetworkConnectionListener() }
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private val directory = File(this.context.filesDir, "download-parts").apply { mkdirs() }
    private val exportJournal = File(directory, "export-commit.json")
    @Volatile private var activeId: String? = null
    @Volatile private var activeCall: Call? = null

    init {
        scope.launch {
            try {
                recoverExportCommit()
                val restored = library.readDownloads().map {
                    if (it.status in activeStatuses) it.copy(
                        status = DownloadStatus.INTERRUPTED,
                        error = "下载已中断，点击重试继续",
                    ) else it
                }
                restored.filter { it.status == DownloadStatus.INTERRUPTED }.forEach { library.saveDownload(it) }
                _jobs.value = restored
                cleanupPendingExports()
                ready.complete(Unit)
            } catch (error: Exception) {
                ready.completeExceptionally(error)
            }
        }
    }

    fun enqueue(tracks: List<Track>) {
        scope.launch {
            try {
                ready.await()
                tracks.distinctBy { it.id }.forEach { track ->
                    if (activeId == track.id) return@forEach
                    val old = _jobs.value.firstOrNull { it.track.id == track.id }
                    if (old?.status in activeStatuses) return@forEach
                    cancelled.remove(track.id)
                    interrupted.remove(track.id)
                    update(DownloadTask(track))
                }
                if (_jobs.value.any { it.status == DownloadStatus.QUEUED }) startService()
            } catch (_: Exception) {
                tracks.forEach { track ->
                    updateMemory(DownloadTask(track, DownloadStatus.FAILED, error = "无法创建下载任务，请检查手机存储空间"))
                }
            }
        }
    }

    fun retry(id: String) {
        val task = _jobs.value.firstOrNull { it.track.id == id } ?: return
        if (task.status !in activeStatuses && task.status != DownloadStatus.COMPLETED) enqueue(listOf(task.track))
    }

    fun forgetCompleted(id: String) {
        scope.launch {
            runCatching {
                ready.await()
                stateMutex.withLock {
                    if (_jobs.value.any { it.track.id == id && it.status == DownloadStatus.COMPLETED }) {
                        library.removeDownload(id)
                        _jobs.value = _jobs.value.filterNot { it.track.id == id }
                    }
                }
            }
        }
    }

    fun cancel(id: String) {
        val task = _jobs.value.firstOrNull { it.track.id == id } ?: return
        if (task.status == DownloadStatus.COMPLETED) return
        cancelled.add(id)
        if (activeId == id) activeCall?.cancel()
        scope.launch {
            stateMutex.withLock {
                val latest = _jobs.value.firstOrNull { it.track.id == id }
                if (latest != null && latest.status != DownloadStatus.COMPLETED) {
                    val next = latest.copy(status = DownloadStatus.CANCELLED, error = null)
                    updateLocked(next)
                }
            }
            if (activeId != id) deleteParts(task.track)
        }
    }

    fun interruptAll() {
        val tasks = _jobs.value.filter { it.status in activeStatuses }
        tasks.forEach { interrupted.add(it.track.id) }
        activeCall?.cancel()
        scope.launch {
            tasks.forEach { task ->
                stateMutex.withLock {
                    val latest = _jobs.value.firstOrNull { it.track.id == task.track.id }
                    if (latest != null && latest.status in activeStatuses) {
                        val next = latest.copy(status = DownloadStatus.INTERRUPTED, error = "下载已中断，点击重试继续")
                        updateLocked(next)
                    }
                }
            }
        }
    }

    suspend fun runPending(onProgress: () -> Unit) = withContext(Dispatchers.IO) {
        ready.await()
        runnerMutex.withLock {
            while (true) {
                currentCoroutineContext().ensureActive()
                val task = _jobs.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
                activeId = task.track.id
                try {
                    download(task.track, onProgress)
                } catch (error: Exception) {
                    val state = when {
                        cancelled.contains(task.track.id) -> DownloadStatus.CANCELLED
                        interrupted.contains(task.track.id) || error is CancellationException -> DownloadStatus.INTERRUPTED
                        else -> DownloadStatus.FAILED
                    }
                    withContext(NonCancellable) {
                        val latest = _jobs.value.firstOrNull { it.track.id == task.track.id } ?: task
                        update(latest.copy(status = state, error = when (state) {
                            DownloadStatus.CANCELLED -> null
                            DownloadStatus.INTERRUPTED -> "下载已中断，点击重试继续"
                            else -> friendlyError(error)
                        }))
                        if (state == DownloadStatus.CANCELLED) deleteParts(task.track)
                    }
                    if (error is CancellationException) throw error
                } finally {
                    activeId = null
                    activeCall = null
                    onProgress()
                }
            }
        }
    }

    private suspend fun download(track: Track, onProgress: () -> Unit) {
        val profile = bilibili.profileSnapshot()
        val requestId = NetworkTrace.requestId()
        checkRunning(track.id)
        val existing = library.saved(track.id)
        if (existing != null) {
            val exists = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(existing.uri), "r")?.use { true } == true }.getOrDefault(false)
            if (exists) {
                update(DownloadTask(track, DownloadStatus.COMPLETED, uri = existing.uri))
                bilibili.log(profile, requestId, mapOf("stage" to "save", "event" to "local_hit"))
                return
            }
            library.removeLocal(track.id)
        }
        update(DownloadTask(track, DownloadStatus.DOWNLOADING))
        onProgress()
        var stream = bilibili.resolveAudio(track, profile, requestId)
        var refreshAttempt = 0
        checkRunning(track.id)
        try {
            transfer(track, stream, profile, requestId, refreshAttempt, onProgress)
        } catch (_: LinkExpiredException) {
            checkRunning(track.id)
            refreshAttempt = 1
            bilibili.log(profile, requestId, mapOf("stage" to "cdn", "event" to "refresh_link", "attempt" to "1"))
            stream = bilibili.resolveAudio(track, profile, requestId)
            checkRunning(track.id)
            transfer(track, stream, profile, requestId, refreshAttempt, onProgress)
        }
        checkRunning(track.id)
        val latest = _jobs.value.first { it.track.id == track.id }
        update(latest.copy(status = DownloadStatus.SAVING))
        onProgress()
        val source = partFile(track)
        val output = File(directory, "${safeId(track)}.m4a")
        var stage = "remux"
        try {
            ensureSpace(source.length() + SPACE_RESERVE)
            remuxAudio(source, output, track, stream.expectedDurationMs)
            checkRunning(track.id)
            stage = "save"
            val uri = publish(track, output)
            // Persist ownership before exposing success. No cancellation is accepted halfway through commit.
            withContext(NonCancellable) {
                try {
                    val completed = latest.copy(status = DownloadStatus.COMPLETED, bytes = output.length(), totalBytes = output.length(), uri = uri.toString(), error = null)
                    library.commitDownload(LocalTrack(track, uri.toString()), completed)
                    updateMemory(completed)
                    bilibili.log(profile, requestId, mapOf("stage" to "save", "event" to "saved", "bytes" to output.length().toString()))
                    exportJournal.delete()
                } catch (error: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    exportJournal.delete()
                    throw error
                }
                deleteParts(track)
            }
        } catch (error: Exception) {
            if (error !is CancellationException) bilibili.log(profile, requestId,
                mapOf("stage" to stage, "event" to "failed", "failure" to NetworkTrace.failureKind(error),
                    "ua" to profile.audioUa.id, "parse_ua" to profile.parseUa.id, "attempt" to refreshAttempt.toString()))
            throw error
        } finally {
            output.delete()
        }
    }

    private suspend fun transfer(track: Track, stream: AudioStream, profile: NetworkProfile, requestId: String, attempt: Int, onProgress: () -> Unit) {
        var lastError: Exception = IOException("没有可用的音频地址")
        for (url in (listOf(stream.url) + stream.backupUrls).distinct().take(3)) {
            checkRunning(track.id)
            try {
                transferUrl(track, stream, url, profile, requestId, attempt, onProgress)
                return
            } catch (error: Exception) {
                if (error is CancellationException || cancelled.contains(track.id) || interrupted.contains(track.id)) throw error
                lastError = error
                if (error is RateLimitedException || error is UnauthorizedMediaException) throw error
            }
        }
        throw lastError
    }

    private suspend fun transferUrl(track: Track, stream: AudioStream, url: String, profile: NetworkProfile, requestId: String, attempt: Int, onProgress: () -> Unit) {
        val part = partFile(track)
        val previous = readResume(track)?.takeIf { it.bitrate == stream.bitrate }
        val offset = part.takeIf { previous != null && DownloadRules.strongEtag(previous.etag) != null }?.length() ?: 0L
        var response = fetch(url, stream, offset, previous?.etag, profile, requestId, attempt)
        var append = offset > 0 && DownloadRules.canAppend(response.code, offset, previous?.total ?: -1, previous?.etag, response.header("ETag"), response.header("Content-Range"))
        if (offset > 0 && !append && response.code !in listOf(401, 403, 404, 410, 412, 429)) {
            response.close()
            response = fetch(url, stream, 0, null, profile, requestId, attempt)
            append = false
        }
        var stage = "content"
        try { response.use {
            checkHttp(it)
            val total = MediaResponsePolicy.validate(it, if (append) offset else 0L)
            val body = it.body ?: throw IOException("音频内容为空")
            stage = "transfer"
            if (total > MAX_AUDIO_BYTES) throw IOException("音频超过 2 GB，请选择较短的视频")
            ensureSpace(max(0, total - if (append) offset else 0) + SPACE_RESERVE)
            var copied = if (append) offset else 0L
            var lastUpdate = 0L
            // Invalidate old entity metadata before truncation. A process death must never
            // leave old bytes paired with the replacement entity's ETag.
            if (!append) resumeFile(track).delete()
            FileOutputStream(part, append).use { output ->
                writeResume(track, ResumeInfo(DownloadRules.strongEtag(it.header("ETag")), total, stream.bitrate))
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkRunning(track.id)
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (copied > MAX_AUDIO_BYTES) throw IOException("音频超过 2 GB，请选择较短的视频")
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 700) {
                            update(DownloadTask(track, DownloadStatus.DOWNLOADING, copied, total))
                            onProgress()
                            lastUpdate = now
                        }
                    }
                }
                output.fd.sync()
            }
            if (!DownloadRules.complete(total, copied)) throw IOException("音频下载不完整，点击重试继续")
            update(DownloadTask(track, DownloadStatus.DOWNLOADING, copied, copied))
            bilibili.log(profile, requestId, mapOf("stage" to "transfer", "event" to "transfer_complete", "bytes" to copied.toString(),
                "attempt" to attempt.toString(), "ua" to profile.audioUa.id), it.request.url.toString())
        } } catch (error: Exception) {
            if (error !is CancellationException) {
                val connection = response.request.tag(ConnectionMetadata::class.java)
                bilibili.log(profile, requestId, mapOf("stage" to stage, "event" to "failed", "http" to response.code.toString(),
                    "failure" to ((error as? InvalidMediaResponseException)?.reason ?: NetworkTrace.failureKind(error)),
                    "ua" to profile.audioUa.id, "parse_ua" to profile.parseUa.id, "attempt" to attempt.toString(),
                    "offset" to (if (append) offset else 0L).toString(), "family" to (connection?.family ?: "unknown"),
                    "proxy" to (connection?.proxy ?: "unknown"), "network_stage" to (connection?.stage ?: "unknown")), response.request.url.toString())
            }
            throw error
        }
    }

    private suspend fun fetch(original: String, stream: AudioStream, offset: Long, etag: String?, profile: NetworkProfile, requestId: String, attempt: Int): Response {
        var url = original
        repeat(5) { redirects ->
            val request = MediaRequestPolicy.request(url, stream, profile.audioUa, offset, etag)
            val call = client.newCall(request)
            activeCall = call
            val response = try { call.await() }
            catch (error: CancellationException) { throw error }
            catch (error: IOException) {
                val connection = request.tag(ConnectionMetadata::class.java)
                bilibili.log(profile, requestId, mapOf("stage" to "cdn", "event" to "request_failed", "ua" to profile.audioUa.id,
                    "parse_ua" to profile.parseUa.id, "attempt" to attempt.toString(), "redirects" to redirects.toString(),
                    "profile" to "web_media", "range" to "open", "family" to (connection?.family ?: "unknown"),
                    "proxy" to (connection?.proxy ?: "unknown"), "network_stage" to (connection?.stage ?: "unknown"),
                    "failure" to NetworkTrace.failureKind(error), "offset" to offset.toString()), url)
                throw error
            }
            val connection = request.tag(ConnectionMetadata::class.java)
            bilibili.log(profile, requestId, mapOf("stage" to "cdn", "http" to response.code.toString(),
                "ua" to profile.audioUa.id, "parse_ua" to profile.parseUa.id, "offset" to offset.toString(),
                "redirects" to redirects.toString(), "profile" to "web_media", "range" to "open", "attempt" to attempt.toString(),
                "family" to (connection?.family ?: "unknown"), "proxy" to (connection?.proxy ?: "unknown")), url)
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                response.close()
                url = next?.toString() ?: throw IOException("音频地址已失效，请重新解析")
            } else return response
        }
        throw IOException("音频地址重定向次数过多")
    }

    private fun checkHttp(response: Response) {
        when (response.code) {
            401 -> throw UnauthorizedMediaException()
            403, 404, 410 -> throw LinkExpiredException(response.code)
            412, 429 -> throw RateLimitedException(response.code)
        }
        if (!response.isSuccessful) throw IOException("下载服务暂时不可用（${response.code}），请稍后重试")
    }

    private suspend fun remuxAudio(source: File, target: File, track: Track, resolvedDurationMs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val audioIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            } ?: throw IOException("此音轨不是受支持的 AAC 格式")
            val format = extractor.getTrackFormat(audioIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val sourceDurationMs = resolvedDurationMs.takeIf { it > 0 } ?: track.durationMs
            if (durationUs <= 0 && sourceDurationMs <= 0) throw IOException("无法确认音频完整时长，请重新解析")
            if (durationUs > 0 && sourceDurationMs > 0 && abs(durationUs / 1000 - sourceDurationMs) > max(5_000L, sourceDurationMs / 100)) {
                throw IOException("音频时长与视频不符，可能只有试看片段")
            }
            extractor.selectTrack(audioIndex)
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            started = true
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            val buffer = ByteBuffer.allocateDirect(max(maxInput, 256 * 1024).coerceAtMost(2 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var samples = 0
            var lastSampleTime = 0L
            while (true) {
                checkRunning(track.id)
                buffer.clear()
                val bytes = extractor.readSampleData(buffer, 0)
                if (bytes < 0) break
                if (bytes > buffer.capacity()) throw IOException("音频分段过大，无法处理")
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw IOException("此音轨受保护，无法导出")
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) throw IOException("此音轨包含无法独立处理的音频帧")
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, bytes, extractor.sampleTime, flags)
                muxer.writeSampleData(outputTrack, buffer, info)
                lastSampleTime = extractor.sampleTime
                samples++
                if (!extractor.advance()) break
            }
            if (samples == 0) throw IOException("音频内容为空")
            val expectedMs = sourceDurationMs.takeIf { it > 0 } ?: durationUs / 1000
            if (expectedMs > 10_000 && lastSampleTime / 1000 < expectedMs - max(3_000L, expectedMs / 50)) {
                throw IOException("音频内容不完整，请重新下载")
            }
            muxer.stop()
            started = false
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            extractor.release()
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
        }
    }

    private suspend fun publish(track: Track, file: File): Uri {
        ensureSpace(file.length() + SPACE_RESERVE)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, DownloadRules.fileName(track.title, track.bvid, track.page))
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/听屿/")
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.ARTIST, track.artist)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("无法创建音乐文件，请检查存储空间")
        try {
            writeExportJournal(ExportCommit(track, uri.toString(), file.length()))
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkRunning(track.id)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("无法写入音乐目录")
            checkRunning(track.id)
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            if (published != 1) throw IOException("无法完成文件保存")
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            exportJournal.delete()
            throw error
        }
    }

    private suspend fun recoverExportCommit() {
        if (!exportJournal.exists()) return
        val record = runCatching { Json.decodeFromString<ExportCommit>(exportJournal.readText()) }.getOrNull()
        if (record == null) { exportJournal.delete(); return }
        val uri = Uri.parse(record.uri)
        // The private journal alone is not evidence that a completed MediaStore entry exists.
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) { exportJournal.delete(); return }
        val resolver = context.contentResolver
        var owned = false
        var pending = true
        var bytes = -1L
        resolver.query(uri, arrayOf(MediaStore.Audio.Media.OWNER_PACKAGE_NAME, MediaStore.Audio.Media.IS_PENDING, MediaStore.Audio.Media.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                owned = cursor.getString(0) == context.packageName
                pending = cursor.getInt(1) == 1
                bytes = cursor.getLong(2)
            }
        }
        val actualBytes = if (owned && !pending) runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        }.getOrDefault(-1) else -1
        val readable = owned && !pending && actualBytes == record.bytes && actualBytes > 0 &&
            (bytes <= 0 || bytes == actualBytes)
        if (readable) {
            library.commitDownload(LocalTrack(record.track, record.uri), DownloadTask(
                record.track, DownloadStatus.COMPLETED, actualBytes, actualBytes, uri = record.uri,
            ))
            deleteParts(record.track)
        } else if (owned && pending) {
            resolver.delete(uri, null, null)
        }
        exportJournal.delete()
    }

    private fun writeExportJournal(record: ExportCommit) {
        val temporary = File(directory, "export-commit.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(Json.encodeToString(record).toByteArray())
            output.fd.sync()
        }
        if (!temporary.renameTo(exportJournal)) {
            temporary.delete()
            throw IOException("无法保存下载记录，请检查存储空间")
        }
    }

    private fun cleanupPendingExports() {
        // Scoped storage limits this query to our own entries. Never delete completed user files.
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.query(collection, arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.IS_PENDING} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.OWNER_PACKAGE_NAME} = ?",
                arrayOf("1", "${Environment.DIRECTORY_MUSIC}/听屿/", context.packageName), null)?.use { cursor ->
                while (cursor.moveToNext()) resolver.delete(Uri.withAppendedPath(collection, cursor.getLong(0).toString()), null, null)
            }
        }
    }

    private suspend fun checkRunning(id: String) {
        currentCoroutineContext().ensureActive()
        if (cancelled.contains(id)) throw DownloadCancelledException()
        if (interrupted.contains(id)) throw DownloadInterruptedException()
    }

    private fun ensureSpace(needed: Long) {
        if (StatFs(directory.absolutePath).availableBytes < needed) throw IOException("手机存储空间不足，请清理后重试")
    }

    private suspend fun update(task: DownloadTask) = stateMutex.withLock {
        updateLocked(task)
    }

    /** Caller holds stateMutex. Only stopped transfers may fall back to memory on write failure. */
    private suspend fun updateLocked(task: DownloadTask) {
        val visible = when (task.status) {
            DownloadStatus.CANCELLED, DownloadStatus.INTERRUPTED, DownloadStatus.FAILED ->
                persistStoppedDownload(task, library::saveDownload)
            else -> {
                library.saveDownload(task)
                task
            }
        }
        _jobs.value = replaceTask(_jobs.value, visible)
    }

    private suspend fun updateMemory(task: DownloadTask) = stateMutex.withLock {
        _jobs.value = replaceTask(_jobs.value, task)
    }

    private fun replaceTask(tasks: List<DownloadTask>, task: DownloadTask): List<DownloadTask> =
        if (tasks.any { it.track.id == task.track.id }) tasks.map { if (it.track.id == task.track.id) task else it } else tasks + task

    private suspend fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (_: RuntimeException) {
            _jobs.value.filter { it.status == DownloadStatus.QUEUED }.forEach {
                update(it.copy(status = DownloadStatus.INTERRUPTED, error = "请返回应用后点击重试，继续下载"))
            }
        }
    }

    private fun safeId(track: Track) = track.id.replace(Regex("[^A-Za-z0-9-]"), "-")
    private fun partFile(track: Track) = File(directory, "${safeId(track)}.part")
    private fun resumeFile(track: Track) = File(directory, "${safeId(track)}.json")
    private fun readResume(track: Track): ResumeInfo? = runCatching { Json.decodeFromString<ResumeInfo>(resumeFile(track).readText()) }.getOrNull()
    private fun writeResume(track: Track, info: ResumeInfo) {
        val target = resumeFile(track)
        val temporary = File(directory, "${safeId(track)}.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(Json.encodeToString(info).toByteArray())
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            target.delete() // Disable resume if atomic metadata replacement fails.
        }
    }
    private fun deleteParts(track: Track) { partFile(track).delete(); resumeFile(track).delete() }

    private fun friendlyError(error: Exception): String = when (error) {
        is RateLimitedException, is LinkExpiredException, is UnauthorizedMediaException -> error.message ?: "音频下载失败，请重新解析"
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "网络连接失败，请检查网络后重试"
        is java.io.InterruptedIOException -> "下载已中断，点击重试继续"
        is IOException -> userMessage(error, "下载失败，请稍后重试")
        else -> "音频处理失败，请重新解析后重试"
    }

    @Serializable private data class ResumeInfo(val etag: String?, val total: Long, val bitrate: Long)
    @Serializable private data class ExportCommit(val track: Track, val uri: String, val bytes: Long)
    private class LinkExpiredException(status: Int) : IOException(DownloadRules.httpMessage(status))
    private class RateLimitedException(status: Int) : IOException(DownloadRules.httpMessage(status))
    private class UnauthorizedMediaException : IOException(DownloadRules.httpMessage(401))
    private class DownloadCancelledException : IOException()
    private class DownloadInterruptedException : IOException()

    companion object {
        private const val SPACE_RESERVE = 16L * 1024 * 1024
        private const val MAX_AUDIO_BYTES = 2L * 1024 * 1024 * 1024
        private val activeStatuses = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.SAVING)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
        }
    })
}
