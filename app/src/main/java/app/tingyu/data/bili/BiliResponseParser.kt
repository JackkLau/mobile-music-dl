package app.tingyu.data.bili

import app.tingyu.model.AudioStream
import app.tingyu.model.ParseResult
import app.tingyu.model.Track
import app.tingyu.model.MusicMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.int(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
internal fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: (long(key) == 1L)
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

internal object BiliResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun root(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: throw BiliException("B 站返回了无法识别的数据，请稍后重试")
    } catch (e: BiliException) { throw e } catch (_: Exception) {
        throw BiliException("B 站暂时未返回有效数据，请稍后重试")
    }

    fun data(root: JsonObject): JsonObject {
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
            ?: throw BiliException("B 站响应格式发生变化，请更新应用")
        if (code != 0) throw apiError(code)
        return root["data"] as? JsonObject ?: throw BiliException("未找到可用内容")
    }

    fun apiError(code: Int): BiliException = BiliException(when (code) {
        -101 -> "登录已失效或此内容需要登录，请重新登录 B 站"
        -403, 403, 10003 -> "当前账号无权访问此内容，请检查收藏夹或视频权限"
        -404, 62002, 62004 -> "视频或收藏夹不存在、已失效或已被删除"
        -352, -412, -401, 412, 429 -> "B 站暂时限制了请求，请稍后重试"
        -400 -> "链接参数不可用，请重新复制链接后解析"
        else -> "B 站暂时无法提供此内容（错误码 $code）"
    }, code)

    fun video(data: JsonObject, requestedPage: Int?): ParseResult {
        val bvid = data.string("bvid").takeIf { Regex("BV[0-9A-Za-z]{10}").matches(it) }
            ?: throw BiliException("未找到有效的视频编号")
        if (data.int("state") < 0) throw BiliException("此视频当前不可访问")
        val title = data.string("title").ifBlank { bvid }
        val owner = data.obj("owner").string("name")
        val cover = secureImageUrl(data.string("pic"))
        val pages = data.array("pages").mapNotNull { it.asObject() }
        val all = pages.mapNotNull { part ->
            val cid = part.long("cid")
            if (cid <= 0) return@mapNotNull null
            val page = part.int("page").coerceAtLeast(1)
            MusicMetadata.normalize(Track(
                bvid = bvid, cid = cid,
                title = if (pages.size > 1) MusicMetadata.partTitle(title, part.string("part"), page) else title,
                artist = owner, coverUrl = cover,
                durationMs = part.long("duration") * 1000L,
                page = page,
                sourceTitle = title,
            ))
        }.ifEmpty {
            val cid = data.long("cid")
            if (cid > 0) listOf(MusicMetadata.normalize(Track(bvid, cid, title, owner, cover, data.long("duration") * 1000))) else emptyList()
        }
        val selected = if (requestedPage != null) all.filter { it.page == requestedPage } else all
        if (selected.isEmpty()) throw BiliException("未找到此视频的分 P 或可用音轨信息")
        return ParseResult(title, selected)
    }

    fun favorites(data: JsonObject, id: Long, page: Int): ParseResult {
        val title = data.obj("info").string("title").ifBlank { "B 站收藏夹" }
        val tracks = data.array("medias").mapNotNull { it.asObject() }.mapNotNull { media ->
            if (media.int("type") != 2) return@mapNotNull null
            val bvid = media.string("bvid").ifEmpty { media.string("bv_id") }
            if (!Regex("BV[0-9A-Za-z]{10}").matches(bvid)) return@mapNotNull null
            // cid=0 represents a list entry. resolveAudio fetches current video details before use.
            MusicMetadata.normalize(Track(
                bvid = bvid, cid = media.obj("ugc").long("first_cid"),
                title = media.string("title").ifBlank { "失效视频" },
                artist = media.obj("upper").string("name"),
                coverUrl = secureImageUrl(media.string("cover")),
                durationMs = 0L, // Collection duration may cover all parts; do not compare it to part 1.
            ))
        }
        return ParseResult(title, tracks, id, if (data.bool("has_more")) page + 1 else null)
    }

    fun audio(
        data: JsonObject,
        expectedDurationMs: Long,
        sourceUrl: String,
        diagnostic: (Map<String, String>) -> Unit = {},
    ): AudioStream {
        if (data.int("drm_tech_type") != 0 || data.bool("is_drm")) throw BiliException("此音轨受保护，当前版本无法导出")
        val preview = data.int("is_preview") == 1 || data.bool("is_preview") ||
            data.array("accept_description").any { (it as? JsonPrimitive)?.contentOrNull?.contains("试看") == true }
        if (preview) throw BiliException("此视频仅返回试看内容，无法获取完整音频")
        val actualDuration = data.long("timelength")
        if (expectedDurationMs <= 0 || actualDuration <= 0) {
            throw BiliException("无法确认音轨的完整时长，请重新解析视频后重试")
        }
        if (expectedDurationMs > 0 && actualDuration > 0 && actualDuration + 3_000 < expectedDurationMs) {
            throw BiliException("B 站返回的音轨时长不完整，请检查账号访问权限")
        }
        val dash = data.obj("dash")
        val audio = dash.array("audio").mapNotNull { it.asObject() }
        if (audio.isEmpty()) {
            diagnostic(mapOf("event" to "selection_failed", "reason" to "no_audio", "audio_total" to "0"))
            throw BiliException("接口未返回独立音轨，请重新解析；当前暂不支持从合并视频流中提取音频")
        }
        val candidates = audio.mapIndexed { index, item ->
            val format = audioFormat(item)
            val urls = mediaUrls(item)
            if (index < 16) diagnostic(mapOf(
                "event" to "candidate", "candidate" to index.toString(),
                "format" to format.confidence.label, "codec" to format.codec, "mime" to format.mime,
                "urls" to urls.size.toString(),
                "reason" to (if (format.confidence == AudioConfidence.REJECTED) format.reason
                    else if (urls.isEmpty()) mediaUrlFailure(item) else "usable"),
            ))
            AudioCandidate(item, format, urls)
        }
        // An unusable primary address does not invalidate an otherwise usable audio
        // representation. Confirmed AAC takes priority over unknown MP4 audio, regardless
        // of bitrate. MediaExtractor still verifies the actual AAC track before publication.
        val supported = candidates.filter { it.format.confidence != AudioConfidence.REJECTED }
        val selected = supported.filter { it.urls.isNotEmpty() }
            .sortedWith(compareBy<AudioCandidate> { it.format.confidence.ordinal }
                .thenByDescending { it.data.long("bandwidth") }).firstOrNull()
        if (selected == null) {
            val reason = if (supported.isNotEmpty()) "no_usable_url" else "unsupported_codec"
            diagnostic(mapOf("event" to "selection_failed", "reason" to reason, "audio_total" to audio.size.toString(),
                "candidates" to supported.size.toString()))
            if (supported.isNotEmpty()) throw BiliException("已找到候选音轨，但接口未提供可用的音频地址，请重新解析")
            throw BiliException("当前音轨信息不满足 AAC/M4A 导出条件，请重新解析或尝试其他视频")
        }
        diagnostic(mapOf("event" to "selected", "format" to selected.format.confidence.label,
            "audio_total" to audio.size.toString(), "candidates" to supported.size.toString(), "urls" to selected.urls.size.toString()))
        return AudioStream(
            url = selected.urls.first(), backupUrls = selected.urls.drop(1), bitrate = selected.data.long("bandwidth"),
            expectedDurationMs = expectedDurationMs,
            // Login cookies belong to the Bilibili API, never the CDN.
            headers = mapOf("Referer" to sourceUrl, "User-Agent" to BilibiliRepository.USER_AGENT),
        )
    }

    private enum class AudioConfidence(val label: String) { AAC("aac"), INSPECT("inspect"), REJECTED("rejected") }
    private data class AudioFormat(val confidence: AudioConfidence, val codec: String, val mime: String, val reason: String)
    private data class AudioCandidate(val data: JsonObject, val format: AudioFormat, val urls: List<String>)

    private fun audioFormat(audio: JsonObject): AudioFormat {
        val mimes = listOf(audio.string("mimeType"), audio.string("mime_type")).filter(String::isNotBlank)
        val types = mimes.map { it.substringBefore(';').trim().lowercase() }
        val mimeLabel = when {
            types.isEmpty() -> "missing"
            types.all { it in setOf("audio/mp4", "audio/x-m4a") } -> "audio_mp4"
            types.all { it in mp4MimeTypes } -> "mp4_or_generic"
            else -> "other"
        }
        // Read both dedicated codec metadata and RFC 6381 codecs parameters. Never log
        // arbitrary server strings; describe unknown values with fixed categories instead.
        val codecValues = (listOf(audio.string("codecs"), audio.string("codec")) + mimes.flatMap { mime ->
            Regex("(?:^|;)\\s*codecs\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
                .findAll(mime).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        }).filter(String::isNotBlank).flatMap { it.trim().removeSurrounding("\"").split(',') }
            .map { it.trim().lowercase() }.filter(String::isNotBlank)
        val classes = codecValues.map(::codecClass)
        val codecLabel = when {
            classes.isEmpty() -> "missing"
            classes.all { it == "aac" } -> "aac"
            classes.all { it == "generic_mp4a" } -> "generic_mp4a"
            classes.any { it in setOf("flac", "dolby", "opus", "mpeg_audio") } -> classes.first { it in setOf("flac", "dolby", "opus", "mpeg_audio") }
            else -> "other_or_conflict"
        }
        fun result(confidence: AudioConfidence, reason: String) = AudioFormat(confidence, codecLabel, mimeLabel, reason)
        if (audio.containsKey("ContentProtection") || audio.containsKey("contentProtection") ||
            audio.int("drm_tech_type") != 0 || audio.bool("is_drm")) return result(AudioConfidence.REJECTED, "protected")
        if (types.any { it !in mp4MimeTypes }) return result(AudioConfidence.REJECTED, "container")
        if (classes.any { it != "aac" && it != "generic_mp4a" }) return result(AudioConfidence.REJECTED, "codec")
        if (classes.any { it == "aac" }) return result(AudioConfidence.AAC, "explicit_aac")
        if (audio.int("id") in setOf(30250, 30251)) return result(AudioConfidence.REJECTED, "non_aac_id")
        if (audio.int("id") in setOf(30216, 30232, 30280)) return result(AudioConfidence.AAC, "known_aac_id")
        if (mimeLabel == "audio_mp4") return result(AudioConfidence.INSPECT, "metadata_missing")
        return result(AudioConfidence.REJECTED, "metadata_missing")
    }

    private val mp4MimeTypes = setOf("audio/mp4", "audio/x-m4a", "video/mp4", "application/mp4", "application/octet-stream")

    private fun codecClass(codec: String): String = when {
        codec == "aac" || codec in setOf("mp4a.66", "mp4a.67", "mp4a.68") -> "aac"
        Regex("mp4a\\.40\\.(\\d{1,2})").matchEntire(codec)?.groupValues?.get(1)?.toIntOrNull() in setOf(2, 5, 29, 39, 42) -> "aac"
        codec in setOf("mp4a", "mp4a.40") -> "generic_mp4a"
        codec == "flac" -> "flac"
        codec in setOf("ac-3", "ec-3") -> "dolby"
        codec == "opus" -> "opus"
        codec in setOf("mp4a.69", "mp4a.6b", "mp3") -> "mpeg_audio"
        else -> "other"
    }

    private fun rawMediaUrls(audio: JsonObject): List<String> =
        (listOf(audio.string("baseUrl"), audio.string("base_url")) +
            (audio.array("backupUrl") + audio.array("backup_url"))
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }).filter(String::isNotBlank)

    private fun mediaUrlFailure(audio: JsonObject): String {
        val urls = rawMediaUrls(audio)
        return if (urls.isEmpty()) "missing_url" else {
            val reasons = urls.mapNotNull(MediaUrlPolicy::rejectionReason).distinct()
            if (reasons.size == 1) reasons.single() else "invalid_urls"
        }
    }

    private fun mediaUrls(audio: JsonObject): List<String> =
        rawMediaUrls(audio).mapNotNull(::safeMediaUrl).distinct()

    fun safeMediaUrl(value: String): String? = MediaUrlPolicy.normalize(value)

    private fun secureImageUrl(value: String): String {
        val url = (if (value.startsWith("//")) "https:$value" else value).toHttpUrlOrNull() ?: return ""
        if (url.host != "hdslb.com" && !url.host.endsWith(".hdslb.com")) return ""
        return url.newBuilder().scheme("https").port(443).build().toString()
    }
}
