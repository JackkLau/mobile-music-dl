package app.tingyu.lyrics

import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {
    @Test fun parsesFractionsRepeatedTimesAndTranslation() {
        val lines = LrcParser.parse("[ti:合成测试]\n[00:01.2][00:03.45]甲\n[00:01.200]First\n[00:04.678]乙\n[00:05.00]")
        assertEquals(listOf(LyricLine(1200, "甲\nFirst"), LyricLine(3450, "甲"), LyricLine(4678, "乙"), LyricLine(5000, "")), lines)
    }

    @Test fun offsetsApplyEvenWhenTagFollowsLyrics() {
        assertEquals(listOf(LyricLine(500, "甲")), LrcParser.parse("[00:01.00]甲\n[offset:+500]"))
        assertEquals(listOf(LyricLine(1500, "甲")), LrcParser.parse("[offset:-500]\n[00:01.00]甲"))
        assertEquals(0L, LrcParser.parse("[offset:5000]\n[00:01]甲").single().timeMs)
    }

    @Test fun rejectsInvalidTimesAndMetadataWithoutLosingValidText() {
        assertEquals(listOf(LyricLine(62_000, "合成文本 [括号]")), LrcParser.parse("[ar:合成歌手]\n[00:99]无效\n[bad]非歌词\n[01:02]合成文本 [括号]"))
        assertTrue(LrcParser.parse("普通歌词没有时间戳").isEmpty())
    }

    @Test fun activeLineHandlesSeekingAndPrelude() {
        val lines = listOf(LyricLine(1000, "甲"), LyricLine(2000, "乙"), LyricLine(3000, ""))
        assertEquals(-1, LrcParser.activeIndex(lines, 999))
        assertEquals(0, LrcParser.activeIndex(lines, 1000))
        assertEquals(1, LrcParser.activeIndex(lines, 2999))
        assertEquals(2, LrcParser.activeIndex(lines, 8000))
        assertEquals(-1, LrcParser.activeIndex(emptyList(), 8000))
    }

    @Test fun boundsFileSizeAndLineCount() {
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse("甲".repeat(LrcParser.MAX_BYTES)) }
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse("[00:01]甲\n".repeat(LrcParser.MAX_LINES + 1)) }
    }
}
