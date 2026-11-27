package app.tingyu.sharing

import app.tingyu.model.MusicSource
import app.tingyu.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicSongPageTest {
    private val track = Track("BV1sVtw6JEeq", 349, "歌曲", page = 3,
        sourceUrl = "https://cdn.invalid/audio.m4s?token=private")

    @Test fun eachPlatformUsesOnlyTheSongIdentityAndKeepsTheBilibiliPart() {
        assertEquals("https://www.bilibili.com/video/BV1sVtw6JEeq?p=3", publicSongPage(track))
        assertEquals("https://music.163.com/song?id=3463213367",
            publicSongPage(track.copy(bvid = "netease-3463213367", source = MusicSource.NETEASE)))
        assertEquals("https://y.qq.com/n/ryqq/songDetail/000a1b2c3d4EFg",
            publicSongPage(track.copy(bvid = "qq-000a1b2c3d4EFg", source = MusicSource.QQ)))
    }

    @Test fun missingOrMalformedIdentitiesNeverBecomeAPublicLink() {
        assertNull(publicSongPage(track.copy(localOnly = true)))
        assertNull(publicSongPage(track.copy(page = 0)))
        assertNull(publicSongPage(track.copy(bvid = "BV1sVtw6JEeq?key=private")))
        assertNull(publicSongPage(track.copy(source = MusicSource.NETEASE, bvid = "netease-1&auth=private")))
        assertNull(publicSongPage(track.copy(source = MusicSource.QQ, bvid = "000a1b2c3d4EFg")))
    }
}
