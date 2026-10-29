package app.tingyu.bili

import app.tingyu.data.bili.BiliException
import app.tingyu.data.bili.BiliLink
import app.tingyu.data.bili.BiliLinkParser
import org.junit.Assert.*
import org.junit.Test

class BiliLinkParserTest {
    @Test fun extractsVideoAndPartFromShareText() {
        val result = BiliLinkParser.parse("喜欢这首歌 https://www.bilibili.com/video/BV1xx411c7mD/?p=2&share_source=copy 。")
        assertEquals(BiliLink.Video(bvid = "BV1xx411c7mD", page = 2), result)
    }

    @Test fun parsesStandaloneIdentifiersAndFavorites() {
        assertEquals(BiliLink.Video(bvid = "BV1xx411c7mD"), BiliLinkParser.parse("BV1xx411c7mD"))
        assertEquals(BiliLink.Video(aid = 170001), BiliLinkParser.parse("av170001"))
        assertEquals(BiliLink.Favorite(12345), BiliLinkParser.parse("https://space.bilibili.com/123/favlist?fid=12345"))
        assertEquals(BiliLink.Favorite(12345), BiliLinkParser.parse("https://www.bilibili.com/list/ml12345?oid=678"))
    }

    @Test fun shortLinksAreRecognizedWithoutNetworkOrCredentials() {
        val result = BiliLinkParser.parse("分享 https://b23.tv/abcdef") as BiliLink.Short
        assertEquals("b23.tv", result.url.host)
    }

    @Test fun desktopMobileAndShareTextHaveSameVideoIdentity() {
        val expected = BiliLink.Video(bvid = "BV1sVtw6JEeq", page = 2)
        listOf(
            "https://www.bilibili.com/video/BV1sVtw6JEeq/?vd_source=ignored&p=2",
            "https://m.bilibili.com/video/BV1sVtw6JEeq?p=2&vd_source=other",
            "【歌曲精选】-哔哩哔哩 https://www.bilibili.com/video/BV1sVtw6JEeq?p=2&share_source=copy",
        ).forEach { assertEquals(expected, BiliLinkParser.parse(it)) }
    }

    @Test fun clientShareShortLinkRetainsItsTokenForSafeExpansion() {
        val result = BiliLinkParser.parse("【汪苏泷歌曲精选【分集+歌词+最高音质】-哔哩哔哩】 https://b23.tv/ZhRmMnf") as BiliLink.Short
        assertEquals("https://b23.tv/ZhRmMnf", result.url.toString())
    }

    @Test fun untrustedAndMalformedLinksAreRejected() {
        listOf(
            "https://bilibili.com.evil.example/video/BV1xx411c7mD",
            "https://www.bilibili.com@evil.example/video/BV1xx411c7mD",
            "https://www.bilibili.com:8443/video/BV1xx411c7mD",
            "https://www.bilibili.com/video/BV1xx411c7mD?p=0",
            "https://space.bilibili.com/12/favlist?fid=-1",
            "javascript:alert(1)",
        ).forEach { input ->
            try { BiliLinkParser.parse(input); fail("Accepted $input") } catch (_: BiliException) { }
        }
    }
    @Test fun markdownAndQuotedSharesDoNotPollutePartNumber() {
        val url = "https://m.bilibili.com/video/BV1sVtw6JEeq?p=2"
        listOf("[播放]($url)", "[播放](<$url>)", "\"$url\"", "'$url'", "`$url`",
            "（$url）", "「$url」", "《$url》", "【分享】$url。", "$url.").forEach { input ->
            assertEquals(input, BiliLink.Video(bvid = "BV1sVtw6JEeq", page = 2), BiliLinkParser.parse(input))
        }
    }

    @Test fun shortLinkWrappersAndUnicodeSpacesAreRemoved() {
        listOf("[分享](https://b23.tv/ZhRmMnf)", "'https://b23.tv/ZhRmMnf'",
            "https://b23.tv/ZhRmMnf\u00a0复制链接", "https://b23.tv/ZhRmMnf\u202f分享",
            "“https://b23.tv/ZhRmMnf”", "https://b23.tv/ZhRmMnf。").forEach { input ->
            assertEquals("https://b23.tv/ZhRmMnf", (BiliLinkParser.parse(input) as BiliLink.Short).url.toString())
        }
    }

    @Test fun copyInsertedZeroWidthCharactersDoNotChangeVideoIdentity() {
        assertEquals(BiliLink.Video(bvid = "BV1sVtw6JEeq", page = 2),
            BiliLinkParser.parse("\uFEFFhttps://m.bilibili.com/video/BV1sVtw\u200B6JEeq?p=2\u2060"))
    }

    @Test fun balancedAndEncodedQueryPunctuationRemainsIntact() {
        val url = "https://b23.tv/ZhRmMnf?label=(live)&value=one!&escaped=%29%5D%26%20&last=done!"
        assertEquals(url, (BiliLinkParser.parse("[分享]($url)") as BiliLink.Short).url.toString())
        val encoded = "https://b23.tv/ZhRmMnf?token=%E2%80%8B%26secret%3Dvalue"
        assertEquals(encoded, (BiliLinkParser.parse(encoded) as BiliLink.Short).url.toString())
    }

    @Test fun markdownEscapedAmpersandDoesNotHidePartParameter() {
        assertEquals(BiliLink.Video(bvid = "BV1sVtw6JEeq", page = 2), BiliLinkParser.parse(
            "[链接](https://www.bilibili.com/video/BV1sVtw6JEeq?vd_source=ignored\\&p=2)"))
    }

    @Test fun shareDescriptionCanPrecedeVideoWithAnUnrelatedUrl() {
        assertEquals(BiliLink.Video(bvid = "BV1sVtw6JEeq"), BiliLinkParser.parse(
            "来源 https://example.com/ 分享：https://m.bilibili.com/video/BV1sVtw6JEeq"))
    }

}
