package app.tingyu.model

/** Conservative title extraction, not audio recognition. Unknown performers remain unknown. */
object MusicMetadata {
    private val spaces = Regex("[\\s\\u00a0\\u2007\\u202f]+")
    private val formatTag = Regex("(?i)^(?:(?:4|8)K|1080P|720P|60FPS|HD|UHD|Hi[- ]?Res|FLAC|AAC|无损(?:音质)?|高(?:清|音质)|超清|音质(?:增强)?|官方(?:音乐)?(?:MV|视频|音频)|Official(?: Music)? (?:Video|Audio)|Official MV|MV|歌词(?:版|视频)?|纯享版|完整版|中字|中英字幕)(?:[ /·+｜|]+(?:(?:4|8)K|60FPS|HDR|无损|高清|音质|MV))*$")
    private val brackets = Regex("【([^【】]*)】|\\[([^\\[\\]]*)]|（([^（）]*)）|\\(([^()]*)\\)")
    private val trailingFormat = Regex("(?i)(?:[\\s·|｜—–-]+|(?<=[》】]))(?:官方(?:音乐)?(?:MV|音频)|Official(?: Music)? (?:Video|Audio)|Official MV|歌词版|歌词视频|纯享版|完整版|MV)\\s*$")
    private val downloadedName = Regex("-BV[0-9A-Za-z]{10}-P[1-9][0-9]*(?: ?\\([0-9]+\\))?(?:\\.m4a)?$", RegexOption.IGNORE_CASE)
    private val legacyPart = Regex(" · P[1-9][0-9]*\\s+(.+)$")
    private val quotes = Regex("《([^《》]+)》")
    private val compound = Regex("(?i)(串烧|合集|歌单|精选|全专辑|完整专辑|\\bmedley\\b|\\bcompilation\\b)")
    private val split = Regex("\\s+[-–—]\\s+")
    private val version = Regex("(?i)^(?:[（(【\\[][^）)】\\]]*(?:live|cover|remix|伴奏|翻唱|现场|纯音乐|演奏)[^）)】\\]]*[）)】\\]]|(?:live|remix|伴奏|现场版|翻唱版|纯音乐))$")
    private val songThenSinger = Regex("^(?:歌名|歌曲|曲名)\\s*[:：]\\s*(.+?)\\s*[|｜;；,，]?\\s*(?:歌手|演唱者?|艺人)\\s*[:：]\\s*(.+)$")
    private val singerThenSong = Regex("^(?:歌手|演唱者?|艺人)\\s*[:：]\\s*(.+?)\\s*[|｜;；,，]?\\s*(?:歌名|歌曲|曲名)\\s*[:：]\\s*(.+)$")

    fun normalize(track: Track): Track {
        if (track.metadataVersion > 0) return track
        val originalTitle = track.sourceTitle.ifBlank { track.title }
        val candidate = legacyPart.find(track.title)?.groupValues?.get(1) ?: track.title
        val cleaned = clean(downloadedName.replace(candidate, ""))
        // A populated local artist field comes from real media tags; do not reinterpret
        // a hyphen inside its song title as an artist/title separator.
        val identity = if (track.localOnly && track.artist.isNotBlank()) track.title to track.artist else extract(cleaned)
        return track.copy(
            title = identity.first.ifBlank { track.title },
            artist = if (track.localOnly && track.artist.isNotBlank()) track.artist else identity.second,
            coverUrl = if (track.localOnly) track.coverUrl else "",
            sourceTitle = originalTitle,
            sourceArtist = track.sourceArtist.ifBlank { if (track.localOnly) "" else track.artist },
            videoCoverUrl = track.videoCoverUrl.ifBlank { if (track.localOnly) "" else track.coverUrl },
            metadataVersion = 1,
        )
    }

    /** Avoid using a container video title when a useful per-song part name is present. */
    fun partTitle(videoTitle: String, partTitle: String, page: Int): String {
        val part = partTitle.trim()
        return if (part.isEmpty() || Regex("(?i)(?:P|分P|第)?\\s*0*$page(?:集|部分)?").matches(part)) videoTitle else part
    }

    private fun extract(text: String): Pair<String, String> {
        val titles = quotes.findAll(text).toList()
        if (titles.size > 1 || compound.containsMatchIn(text)) return text to ""
        songThenSinger.matchEntire(text)?.let {
            val song = unquote(it.groupValues[1].trim())
            val artist = performer(it.groupValues[2])
            if (song.isNotBlank() && artist != null) return song to artist
        }
        singerThenSong.matchEntire(text)?.let {
            val artist = performer(it.groupValues[1])
            val song = unquote(it.groupValues[2].trim())
            if (song.isNotBlank() && artist != null) return song to artist
        }
        if (titles.size == 1) {
            val quote = titles.single()
            val before = text.substring(0, quote.range.first).trim().trimEnd('-', '—', '–', ':', '：').trim()
            val after = text.substring(quote.range.last + 1).trim()
            val suffix = if (version.matches(after)) " $after" else ""
            val artist = performer(before)
            // Text after a quoted song is only a performer when explicitly separated/labeled.
            val afterArtist = if (Regex("^(?:[-—–:：]|歌手[:：]|演唱[:：])").containsMatchIn(after))
                performer(after.replaceFirst(Regex("^(?:[-—–:：]|歌手[:：]|演唱[:：])\\s*"), "")) else null
            if (artist != null && (after.isEmpty() || suffix.isNotEmpty())) return (quote.groupValues[1] + suffix) to artist
            if (before.isEmpty() && afterArtist != null) return quote.groupValues[1] to afterArtist
            if (before.isEmpty() && (after.isEmpty() || suffix.isNotEmpty())) return (quote.groupValues[1] + suffix) to ""
            // Preserve uncertain surrounding prose, especially edition/cover information.
            return text to ""
        }
        val pieces = split.split(text)
        if (pieces.size == 2) {
            val artist = performer(pieces[0])
            val song = pieces[1].trim()
            if (artist != null && song.isNotBlank() && !formatTag.matches(song)) return song to artist
        }
        return text to ""
    }

    private fun clean(value: String): String {
        var text = spaces.replace(value.replace("\u200B", "").replace("\uFEFF", ""), " ").trim()
        text = brackets.replace(text) { match ->
            val content = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty().trim()
            if (formatTag.matches(content)) " " else match.value
        }
        text = spaces.replace(text, " ").trim()
        text = trailingFormat.replace(text, "").trim()
        return text
    }

    private fun performer(value: String): String? {
        val name = value.trim().removePrefix("【").removeSuffix("】").trim()
        if (name.isBlank() || name.length > 64 || formatTag.matches(name) || compound.containsMatchIn(name)) return null
        if (Regex("[《》。！？!?：:；;]|推荐|好听|经典|分享|最爱|一首|首歌|听哭|演唱会|音乐现场|歌词|官方|\\b(?:cover|remix|live)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) return null
        return name
    }

    private fun unquote(value: String): String = value.removeSurrounding("《", "》")
}
