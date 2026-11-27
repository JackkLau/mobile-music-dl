package app.tingyu.bili

import app.tingyu.data.bili.BiliResponseParser
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliMusicMetadataTest {
    @Test fun albumAndPartNumberMustBothMatchBeforeExtractingArtistAndSong() {
        val title = "【黑胶试听合輯】周杰伦_葉惠美"
        val data = BiliResponseParser.root("""{"bvid":"BV1sp4y1B7bH","title":"$title","owner":{"name":"上传者"},
            "pages":[{"cid":275493414,"page":1,"part":"葉惠美_01_以父之名"},
                     {"cid":2,"page":2,"part":"别的专辑_02_未确认"},
                     {"cid":3,"page":3,"part":"葉惠美_08_未确认"}]}""")
        val tracks = BiliResponseParser.video(data, null).tracks
        assertEquals("以父之名", tracks.first().title)
        assertEquals("周杰伦", tracks.first().artist)
        assertEquals(title, tracks.first().sourceTitle)
        assertEquals("上传者", tracks.first().sourceArtist)
        assertEquals(listOf("别的专辑_02_未确认", "葉惠美_08_未确认"), tracks.drop(1).map { it.title })
        assertEquals(listOf("", ""), tracks.drop(1).map { it.artist })
    }

    @Test fun singleVideoUsesSongAndSingerWithoutVideoArtwork() {
        val data = BiliResponseParser.root("""{"bvid":"BV1xx411c7mD","cid":42,
            "title":"【4K】林间客《归舟》官方MV","owner":{"name":"音乐搬运站"},
            "pic":"https://i0.hdslb.com/video.jpg","duration":180}""")
        val track = BiliResponseParser.video(data, null).tracks.single()
        assertEquals("归舟", track.title)
        assertEquals("林间客", track.artist)
        assertEquals("", track.coverUrl)
    }

    @Test fun multiplePartsDoNotPrefixEverySongWithCompilationTitle() {
        val data = BiliResponseParser.root("""{"bvid":"BV1xx411c7mD","title":"夜行音乐精选",
            "owner":{"name":"歌单收藏者"},"pages":[
                {"cid":41,"page":1,"part":"林间客 - 归舟","duration":180},
                {"cid":42,"page":2,"part":"海风 - 山的回声 (Live)","duration":190}]}""")
        val tracks = BiliResponseParser.video(data, null).tracks
        assertEquals(listOf("归舟", "山的回声 (Live)"), tracks.map { it.title })
        assertEquals(listOf("林间客", "海风"), tracks.map { it.artist })
        assertEquals(listOf(41L, 42L), tracks.map { it.cid })
        assertEquals(listOf(1, 2), tracks.map { it.page })
        assertEquals(listOf("夜行音乐精选", "夜行音乐精选"), tracks.map { it.sourceTitle })
        assertEquals(listOf("歌单收藏者", "歌单收藏者"), tracks.map { it.sourceArtist })
    }

    @Test fun favoriteDoesNotIdentifyUploaderAsSinger() {
        val data = BiliResponseParser.root("""{"info":{"title":"我的音乐"},"medias":[
            {"type":2,"bvid":"BV1xx411c7mD","title":"雨夜小记","upper":{"name":"搬运者"},
             "cover":"https://i0.hdslb.com/video.jpg"}]}""")
        val track = BiliResponseParser.favorites(data, 10, 1).tracks.single()
        assertEquals("雨夜小记", track.title)
        assertEquals("", track.artist)
        assertEquals("", track.coverUrl)
    }
}
