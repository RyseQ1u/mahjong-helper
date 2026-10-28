package com.weile.liuzhou.mahjong.domain.mahjong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCountsTest {

    @Test
    fun `牌谱解析与展开`() {
        val counts = TileCounts.parse("123m456p789s11s22s")
        assertEquals(13, counts.total)
        assertEquals(1, counts.countOf(Tile.manzu(1)))
        assertEquals(1, counts.countOf(Tile.manzu(3)))
        assertEquals(2, counts.countOf(Tile.souzu(1)))
        assertEquals(2, counts.countOf(Tile.souzu(2)))
        val tiles = counts.toSortedTiles()
        assertEquals(13, tiles.size)
        assertEquals(Tile.manzu(1), tiles.first())
        assertEquals(Tile.souzu(9), tiles.last())
    }

    @Test
    fun `守恒校验通过`() {
        val counts = TileCounts.parse("123m456p789s11s22s")
        assertTrue(counts.validate() is TileCounts.ValidationResult.Ok)
        // 14 张（自摸后）
        val counts14 = TileCounts.parse("123m456p789s11s234s")
        assertTrue(counts14.validate() is TileCounts.ValidationResult.Ok)
        // 有副露：10 张手牌 + 1 副露
        val withMeld = TileCounts.parse("123m456p78s11s")
        assertTrue(withMeld.validate(melds = 1) is TileCounts.ValidationResult.Ok)
    }

    @Test
    fun `超过四张报错`() {
        // 13 张合法总数，但一万出现 5 张
        val counts = TileCounts.parse("11111m234p567s89s")
        val result = counts.validate()
        assertTrue(result is TileCounts.ValidationResult.Invalid)
        assertTrue((result as TileCounts.ValidationResult.Invalid).reason.contains("一万"))
    }

    @Test
    fun `张数与副露不匹配报错`() {
        val counts = TileCounts.parse("123m456p789s11s2s") // 13 张
        val result = counts.validate(melds = 1) // 配 1 副露应为 10/13 张
        assertTrue(result is TileCounts.ValidationResult.Invalid)
    }

    @Test
    fun `无箭牌牌集拒绝箭牌`() {
        val counts = TileCounts(hasDragons = false)
        var threw = false
        try {
            counts.add(Tile.dragon(0))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `无箭牌牌集 31 种`() {
        val counts = TileCounts(hasDragons = false)
        assertEquals(31, counts.kindCount)
        counts.add(Tile.wind(3)) // 北风允许
        assertEquals(1, counts.total)
    }

    @Test
    fun `拷贝独立`() {
        val a = TileCounts.parse("123m456p789s11s22s")
        val b = a.copy()
        b.remove(Tile.manzu(1))
        assertEquals(1, a.countOf(Tile.manzu(1)))
        assertEquals(0, b.countOf(Tile.manzu(1)))
        assertEquals(13, a.total)
        assertEquals(12, b.total)
    }

    @Test
    fun `无法解析的牌谱抛异常`() {
        var threw = false
        try {
            TileCounts.parse("123m45Xp")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(TileCounts.parse("EE").total == 0)
    }
}
