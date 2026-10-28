package com.weile.liuzhou.mahjong.domain.mahjong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileTest {

    @Test
    fun `编码与牌名`() {
        assertEquals("一万", Tile.manzu(1).displayName)
        assertEquals("九万", Tile.manzu(9).displayName)
        assertEquals("五筒", Tile.pinzu(5).displayName)
        assertEquals("七条", Tile.souzu(7).displayName)
        assertEquals("东风", Tile.wind(0).displayName)
        assertEquals("北风", Tile.wind(3).displayName)
        assertEquals("红中", Tile.dragon(0).displayName)
        assertEquals("发财", Tile.dragon(1).displayName)
        assertEquals("白板", Tile.dragon(2).displayName)
    }

    @Test
    fun `属性判定`() {
        assertTrue(Tile.manzu(1).isTerminal)
        assertTrue(Tile.manzu(9).isTerminal)
        assertTrue(Tile.manzu(5).isSimple)
        assertFalse(Tile.manzu(5).isHonor)
        assertTrue(Tile.wind(0).isWind)
        assertTrue(Tile.dragon(0).isDragon)
        assertTrue(Tile.dragon(0).isHonor)
        assertEquals(0, Tile.manzu(1).suit)
        assertEquals(1, Tile.pinzu(1).suit)
        assertEquals(2, Tile.souzu(1).suit)
        assertEquals(3, Tile.wind(0).suit)
        assertEquals(4, Tile.dragon(0).suit)
    }

    @Test
    fun `牌谱解析`() {
        assertEquals(Tile.manzu(5), Tile.parse("5m"))
        assertEquals(Tile.pinzu(9), Tile.parse("9p"))
        assertEquals(Tile.souzu(1), Tile.parse("1s"))
        assertEquals(Tile.wind(0), Tile.parse("E"))
        assertEquals(Tile.wind(1), Tile.parse("S"))
        assertEquals(Tile.wind(2), Tile.parse("W"))
        assertEquals(Tile.wind(3), Tile.parse("N"))
        assertEquals(Tile.dragon(0), Tile.parse("C"))
        assertEquals(Tile.dragon(1), Tile.parse("F"))
        assertEquals(Tile.dragon(2), Tile.parse("B"))
        assertEquals(Tile.dragon(0), Tile.parse("1z"))
        assertEquals(Tile.wind(3), Tile.parse("7z"))
        assertNull(Tile.parse("X"))
        assertNull(Tile.parse("0m"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `编码越界抛异常`() {
        Tile(34)
    }
}
