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

    @Test fun bomBeforeOffsetDoesNotChangeTheLyricClock() {
        val withoutBom = "[offset:1000]\n[00:05]合成歌词"
        assertEquals(listOf(LyricLine(4000, "合成歌词")), LrcParser.parse("\uFEFF$withoutBom"))
        assertEquals(LrcParser.parse(withoutBom), LrcParser.parse("\uFEFF$withoutBom"))
        assertEquals(listOf(LyricLine(5500, "合成歌词")), LrcParser.parse("\uFEFF[offset:-500]\n[00:05]合成歌词"))
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

    @Test fun userOffsetDelaysOrAdvancesHighlightWithoutEditingLrc() {
        val lines = LrcParser.parse("[00:01]甲\n[00:03]乙")
        assertEquals(-1, LrcParser.activeIndex(lines, 1000, 500))
        assertEquals(0, LrcParser.activeIndex(lines, 1500, 500))
        assertEquals(1, LrcParser.activeIndex(lines, 2500, -500))
        assertEquals(-1, LrcParser.activeIndex(lines, 0, -500))
    }

    @Test fun selectingLineUsesTheSameCalibratedTimeAsHighlight() {
        val lines = LrcParser.parse("[offset:200]\n[00:01]甲\n[00:03]乙")
        val target = LrcParser.seekPosition(lines[1], 500, 10_000)
        assertEquals(3300L, target)
        assertEquals(1, LrcParser.activeIndex(lines, target, 500))
        assertEquals(0, LrcParser.activeIndex(lines, target - 1, 500))
    }

    @Test fun offsetAndSeekBoundsDoNotOverflowOrSeekOutsideAudio() {
        assertEquals(30_000L, LrcParser.clampOffset(Long.MAX_VALUE))
        assertEquals(-30_000L, LrcParser.clampOffset(Long.MIN_VALUE))
        assertEquals(0L, LrcParser.seekPosition(LyricLine(100, "甲"), -500, 1000))
        assertEquals(1000L, LrcParser.seekPosition(LyricLine(900, "甲"), 500, 1000))
        assertEquals(0L, LrcParser.seekPosition(LyricLine(900, "甲"), 500, -1))
        assertEquals(Long.MAX_VALUE, LrcParser.seekPosition(LyricLine(Long.MAX_VALUE, "甲"), 500))
        assertEquals(0, LrcParser.activeIndex(listOf(LyricLine(Long.MAX_VALUE, "甲")), Long.MAX_VALUE, -500))
        assertEquals(-1, LrcParser.activeIndex(listOf(LyricLine(0, "甲")), Long.MIN_VALUE, 500))
    }
}
