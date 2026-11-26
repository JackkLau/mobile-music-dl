package app.tingyu.music

import app.tingyu.data.bili.BiliResponse
import app.tingyu.data.bili.BiliTransport
import app.tingyu.data.music.*
import app.tingyu.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MusicShareTest {
    private val qqId = "0039MnYb0qxYhV"
    private val qq = "https://y.qq.com/n/ryqq_v2/songDetail/$qqId"
    private val net = "https://music.163.com/#/song?id=191232"
    private val neInfo = """{"code":200,"songs":[{"id":191232,"name":"遥远的她","artists":[{"name":"张学友"}],"duration":258000}]}"""
    private val neAsset = """{"code":200,"data":{"id":191232,"code":200,"type":"mp3","size":12345,"url":"https://m701.music.126.net/track.mp3?key=x"}}"""
    private val qqInfo = """{"code":0,"song":{"code":0,"data":{"track_info":{"mid":"$qqId","id":97773,"name":"晴天","singer":[{"name":"周杰伦"}],"interval":269,"action":{"switch":17},"pay":{"pay_down":0},"file":{"media_mid":"$qqId","size_128mp3":12345}}}}}"""
    private val qqAsset = """{"code":0,"song":{"code":0,"data":{"sip":["https://dl.stream.qqmusic.qq.com/"],"midurlinfo":[{"result":0,"songmid":"$qqId","purl":"M500$qqId.mp3?key=x"}]}}}"""

    @Test fun unwrapsShareTextAndDistinguishesSongIdsFromPlaylists() {
        val share = MusicShareLink.first("推荐 [歌曲]($net) 【分享】")!!
        assertEquals("191232", MusicShareLink.direct(share, MusicSource.NETEASE)?.id)
        for (link in listOf(qq,"https://y.qq.com/n/yqq/song/$qqId.html","https://i.y.qq.com/v8/playsong.html?songmid=$qqId"))
            assertEquals(qqId, MusicShareLink.direct(link.toHttpUrl(), MusicSource.QQ)?.id)
        assertTrue(MusicShareLink.direct("https://i.y.qq.com/v8/playsong.html?songid=97773".toHttpUrl(), MusicSource.QQ)!!.numericQqId)
        assertNull(MusicShareLink.direct("https://music.163.com/playlist?id=191232".toHttpUrl(),MusicSource.NETEASE))
        assertNull(MusicShareLink.first("https://y.qq.com.example.net/n/ryqq/songDetail/$qqId"))
        assertThrows(IOException::class.java) { MusicShareLink.direct("https://me@y.qq.com:444/song/$qqId".toHttpUrl(),MusicSource.QQ) }
    }

    @Test fun resolvesShortShareThenRequestsAnAuthorizedFileWithoutSharingCookies(): Unit = runBlocking {
        val calls = mutableListOf<Pair<String,String?>>()
        val repo = MusicShareRepository(BiliTransport { u,h ->
            calls.add(u.host to h["Cookie"])
            when {
                u.host=="163cn.tv" -> BiliResponse(302,"","https://y.music.163.com/m/song?id=191232")
                u.encodedPath.contains("/download/") -> BiliResponse(200,neAsset)
                else -> BiliResponse(200,neInfo)
            }
        }) { "MUSIC_U=fixture" }
        val track = repo.parse("https://163cn.tv/aBc".toHttpUrl()).tracks.single()
        assertEquals("netease-191232:p1",track.id)
        assertEquals("张学友",track.artist)
        val audio = repo.resolve(track)
        assertEquals(12345L,audio.expectedBytes)
        assertEquals(listOf(null,null,"MUSIC_U=fixture"),calls.map { it.second })
        val request = MusicMedia.request(audio.url,track.source,0,null)
        assertNull(request.header("Cookie"))
        assertEquals("bytes=0-",request.header("Range"))
        assertThrows(IOException::class.java) { MusicMedia.request("https://y.qq.com/media",track.source,0,null) }
    }

    @Test fun refusesCrossSiteRedirectsAndLoops() {
        for (location in listOf("https://unknown.example/song?id=1", "https://y.qq.com/song/$qqId","https://163cn.tv/loop")) {
            var calls = 0
            val repo = MusicShareRepository(BiliTransport { _,_ -> calls++; BiliResponse(302,"",location) })
            assertThrows(IOException::class.java) { runBlocking { repo.parse("https://163cn.tv/loop".toHttpUrl()) } }
            assertTrue(calls in 1..6)
        }
    }

    @Test fun qqChecksDownloadRightsAndReturnedSongBeforeAcceptingFile() = runBlocking {
        fun repository(info: String, asset: String = qqAsset) = MusicShareRepository(BiliTransport { u,h ->
            assertNull(h["Cookie"])
            BiliResponse(200, if (u.queryParameter("data")!!.contains("CgiGetVkey")) asset else info)
        })
        val track = repository(qqInfo).parse(qq.toHttpUrl()).tracks.single()
        assertEquals(MusicSource.QQ,track.source)
        assertEquals(269000L,track.durationMs)
        assertEquals(12345L,repository(qqInfo).resolve(track).expectedBytes)
        for (bad in listOf(repository(qqInfo.replace("\"pay_down\":0","\"pay_down\":1")),
            repository(qqInfo.replace("\"switch\":17","\"switch\":1")),
            repository(qqInfo, qqAsset.replace("M500","preview")),repository(qqInfo,qqAsset.replace(qqId,"anotherSong")))) {
            assertThrows(IOException::class.java) { runBlocking { bad.resolve(track) } }
        }
    }

    @Test fun neteaseDenialsPreviewsAndMismatchedTracksAreNotDownloads() = runBlocking {
        fun repository(asset: String) = MusicShareRepository(BiliTransport { u,_ ->
            BiliResponse(200,if (u.encodedPath.contains("download")) asset else neInfo)
        })
        val track = repository(neAsset).parse(net.toHttpUrl()).tracks.single()
        for (bad in listOf("""{"code":301,"data":null}""", neAsset.replace("191232","98765"),
            neAsset.replace("\"size\":","\"freeTrialInfo\":{},\"size\":"),neAsset.replace("m701.music.126.net","example.org"))) {
            assertThrows(IOException::class.java) { runBlocking { repository(bad).resolve(track) } }
        }
    }
}
