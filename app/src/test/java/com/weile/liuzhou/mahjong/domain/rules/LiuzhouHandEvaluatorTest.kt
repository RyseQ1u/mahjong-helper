package com.weile.liuzhou.mahjong.domain.rules

import com.weile.liuzhou.mahjong.domain.game.MeldType
import com.weile.liuzhou.mahjong.domain.game.OpenMeld
import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import com.weile.liuzhou.mahjong.domain.mahjong.TileCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 柳州胡型判定测试（纯 JVM）。
 */
class LiuzhouHandEvaluatorTest {

    private fun hand(spec: String) = TileCounts.parse(spec)

    private fun discardWin(concealed: Boolean = false) =
        WinContext(selfDraw = false, concealed = concealed)

    @Test
    fun `基本胡点炮`() {
        val e = LiuzhouHandEvaluator.evaluate(
            hand("123m456p789s11s234s"), emptyList(), discardWin()
        )
        assertTrue(e.canWin)
        assertTrue(e.patterns.contains(LiuzhouPattern.BASIC))
        assertEquals(0, e.meldCount)
    }

    @Test
    fun `对对胡`() {
        val e = LiuzhouHandEvaluator.evaluate(
            hand("111m222p333s444s55s"), emptyList(), discardWin()
        )
        assertTrue(e.canWin)
        assertTrue(e.patterns.contains(LiuzhouPattern.ALL_TRIPLETS))
    }

    @Test
    fun `清一色`() {
        val e = LiuzhouHandEvaluator.evaluate(
            hand("111m222m333m444m55m"), emptyList(), discardWin()
        )
        assertTrue(e.patterns.contains(LiuzhouPattern.PURE_ONE_SUIT))
    }

    @Test
    fun `小七对`() {
        val e = LiuzhouHandEvaluator.evaluate(
            hand("1122m3344p5566s7s7s"), emptyList(), discardWin()
        )
        assertTrue(e.patterns.contains(LiuzhouPattern.SEVEN_PAIRS))
    }

    @Test
    fun `不能胡返回空牌型`() {
        val e = LiuzhouHandEvaluator.evaluate(
            hand("123m456p789s11s9p1p"), emptyList(), discardWin()
        )
        assertFalse(e.canWin)
        assertTrue(e.patterns.isEmpty())
    }

    @Test
    fun `全求人四副露一手牌`() {
        val melds = listOf(
            OpenMeld(MeldType.PON, List(3) { Tile.manzu(1) }, Seat.TOP),
            OpenMeld(MeldType.PON, List(3) { Tile.manzu(3) }, Seat.TOP),
            OpenMeld(MeldType.PON, List(3) { Tile.manzu(5) }, Seat.TOP),
            OpenMeld(MeldType.PON, List(3) { Tile.manzu(7) }, Seat.TOP),
        )
        val e = LiuzhouHandEvaluator.evaluate(hand("9m"), melds, discardWin())
        assertTrue(e.canWin)
        assertTrue(e.patterns.contains(LiuzhouPattern.QUANQIUREN))
        // 基础胡型按副露推导：全碰 → 对对胡
        assertEquals(LiuzhouPattern.ALL_TRIPLETS, e.quanqiurenBase)
        assertEquals(4, e.meldCount)
    }

    @Test
    fun `全求人基础为清一色`() {
        val melds = listOf(
            OpenMeld(MeldType.CHI, listOf(Tile.manzu(1), Tile.manzu(2), Tile.manzu(3)), Seat.LEFT),
            OpenMeld(MeldType.CHI, listOf(Tile.manzu(4), Tile.manzu(5), Tile.manzu(6)), Seat.LEFT),
            OpenMeld(MeldType.CHI, listOf(Tile.manzu(7), Tile.manzu(8), Tile.manzu(9)), Seat.TOP),
            OpenMeld(MeldType.CHI, listOf(Tile.pinzu(1), Tile.pinzu(2), Tile.pinzu(3)), Seat.TOP),
        )
        val e = LiuzhouHandEvaluator.evaluate(hand("9m"), melds, discardWin())
        assertEquals(LiuzhouPattern.QUANQIUREN, e.patterns.single())
        // 副露混万筒 → 基础为基本胡
        assertEquals(LiuzhouPattern.BASIC, e.quanqiurenBase)
    }

    @Test
    fun `全求人含加杠仍判全刻子`() {
        val melds = listOf(
            OpenMeld(MeldType.PON, List(3) { Tile.manzu(1) }, Seat.TOP),
            OpenMeld(MeldType.PON, List(3) { Tile.manzu(3) }, Seat.TOP),
            OpenMeld(MeldType.SHOUMINKAN, List(4) { Tile.manzu(5) }, Seat.TOP),
            OpenMeld(MeldType.ANKAN, List(4) { Tile.manzu(7) }, Seat.TOP),
        )
        val e = LiuzhouHandEvaluator.evaluate(hand("9m"), melds, discardWin())
        assertTrue(e.canWin)
        assertEquals(LiuzhouPattern.QUANQIUREN, e.patterns.single())
        assertEquals(LiuzhouPattern.ALL_TRIPLETS, e.quanqiurenBase)
    }

    @Test
    fun `门清标志来自上下文`() {
        val e = LiuzhouHandEvaluator.evaluate(
            hand("123m456p789s11s234s"), emptyList(), discardWin(concealed = true)
        )
        assertTrue(e.context.concealed)
        // 门清不是胡型，只是上下文
        assertFalse(e.patterns.contains(LiuzhouPattern.QUANQIUREN))
    }
}
