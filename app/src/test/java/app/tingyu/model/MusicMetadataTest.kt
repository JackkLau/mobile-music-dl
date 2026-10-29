package app.tingyu.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MusicMetadataTest {
    private fun remote(title: String) = Track("BV1xx411c7mD", 42, title, "分享者", "https://i0.hdslb.com/video.jpg", 180_000)

    @Test fun extractsQuotedSongWithoutMisusingUploaderOrVideoThumbnail() {
        val original = remote("【4K】【音质】林间客《归舟》官方MV")
        val normalized = MusicMetadata.normalize(original)
        assertEquals("归舟", normalized.title)
        assertEquals("林间客", normalized.artist)
        assertEquals("", normalized.coverUrl)
        assertEquals(original.coverUrl, normalized.videoCoverUrl)
        assertEquals(original.title, normalized.sourceTitle)
        assertEquals(original.artist, normalized.sourceArtist)
        assertEquals(original.id, normalized.id)
        assertEquals(original.cid, normalized.cid)
        assertEquals(original.durationMs, normalized.durationMs)
        assertEquals(original.sourceUrl, normalized.sourceUrl)
    }

    @Test fun ambiguousTitleKeepsTextAndDoesNotInventSinger() {
        val track = MusicMetadata.normalize(remote("今天录下的一段声音"))
        assertEquals("今天录下的一段声音", track.title)
        assertEquals("", track.artist)
    }

    @Test fun explicitLabelsSupportBothOrders() {
        listOf("歌名：归舟｜歌手：林间客", "演唱：林间客；歌曲：《归舟》").forEach {
            val track = MusicMetadata.normalize(remote(it))
            assertEquals("归舟", track.title)
            assertEquals("林间客", track.artist)
        }
    }

    @Test fun quotedTitleCanPrecedeExplicitlySeparatedSinger() {
        val track = MusicMetadata.normalize(remote("《归舟》 - 林间客"))
        assertEquals("归舟", track.title)
        assertEquals("林间客", track.artist)
    }

    @Test fun presentationNoiseIsRemovedWhileVersionsRemain() {
        listOf("Live", "海风 Remix", "cover 海风", "现场版", "伴奏").forEach { version ->
            val track = MusicMetadata.normalize(remote("【无损音质】林间客 - 归舟 ($version) 【歌词版】"))
            assertEquals("归舟 ($version)", track.title)
            assertEquals("林间客", track.artist)
        }
        assertEquals("归舟 (Live)", MusicMetadata.normalize(remote("林间客《归舟》(Live)")).title)
    }

    @Test fun medleysAndMultipleTitlesAreNotMistakenForOneSong() {
        listOf("林间客《归舟》+《微光》", "林间客 - 雨夜串烧", "林间客《归舟》精选合集").forEach {
            val track = MusicMetadata.normalize(remote(it))
            assertEquals(it, track.title)
            assertEquals("", track.artist)
        }
    }

    @Test fun unspacedHyphensAndUnknownBracketsArePreserved() {
        assertEquals("X-Ray", MusicMetadata.normalize(remote("X-Ray")).title)
        assertEquals("[雨]归舟", MusicMetadata.normalize(remote("[雨]归舟")).title)
    }

    @Test fun oldSerializedTrackNormalizesWithoutChangingPlaybackIdentity() {
        val old = Json.decodeFromString<Track>("""{"bvid":"BV1xx411c7mD","cid":42,"title":"歌单 · P2 林间客 - 归舟","artist":"分享者","page":2}""")
        val normalized = MusicMetadata.normalize(old)
        assertEquals("归舟", normalized.title)
        assertEquals("林间客", normalized.artist)
        assertEquals("BV1xx411c7mD:p2", normalized.id)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=2", normalized.sourceUrl)
        assertEquals(normalized, Json.decodeFromString<Track>(Json.encodeToString(normalized)))
        assertEquals(normalized, MusicMetadata.normalize(normalized))
    }

    @Test fun localEmbeddedTagsAndCoverRemainAuthoritative() {
        val local = remote("归舟 - 雨夜").copy(localOnly = true, artist = "本地标签歌手", coverUrl = "content://local/art")
        val normalized = MusicMetadata.normalize(local)
        assertEquals(local.title, normalized.title)
        assertEquals(local.artist, normalized.artist)
        assertEquals(local.coverUrl, normalized.coverUrl)
        assertEquals("", normalized.sourceArtist)
        assertEquals("", normalized.videoCoverUrl)
    }

    @Test fun restoredFilenameDropsOnlyKnownDownloadSuffix() {
        val local = remote("林间客 - 归舟-BV1xx411c7mD-P2 (1).m4a").copy(localOnly = true, artist = "", coverUrl = "")
        val normalized = MusicMetadata.normalize(local)
        assertEquals("归舟", normalized.title)
        assertEquals("林间客", normalized.artist)
    }

    @Test fun alreadyEditedMetadataCannotBeReinterpretedOrLoseCover() {
        val edited = remote("标题 - 保留").copy(artist = "自填歌手", coverUrl = "content://art", metadataVersion = 1)
        assertSame(edited, MusicMetadata.normalize(edited))
    }

    @Test fun blankOrGenericPartUsesVideoNameButRealPartDoesNot() {
        listOf("", " P02 ", "第2集").forEach { assertEquals("视频名", MusicMetadata.partTitle("视频名", it, 2)) }
        assertEquals("歌手 - 歌曲", MusicMetadata.partTitle("视频名", "歌手 - 歌曲", 2))
    }
}
