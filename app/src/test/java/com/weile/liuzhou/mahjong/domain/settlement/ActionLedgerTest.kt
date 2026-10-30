package com.weile.liuzhou.mahjong.domain.settlement

import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ActionLedger 测试：吃三比计数、杠分台账、最后一张判定（纯 JVM）。
 */
class ActionLedgerTest {

    @Test
    fun `同来源吃碰杠累计吃三比`() {
        val ledger = ActionLedger()
        // 下家从对家来源吃三次
        ledger.record(LedgerEventType.CHI, Seat.RIGHT, Tile.manzu(1), fromSeat = Seat.TOP)
        ledger.record(LedgerEventType.PON, Seat.RIGHT, Tile.manzu(5), fromSeat = Seat.TOP)
        ledger.record(LedgerEventType.MINKAN, Seat.RIGHT, Tile.manzu(9), fromSeat = Seat.TOP)

        assertEquals(3, ledger.meldCountFromSameSource(Seat.RIGHT, Seat.TOP))
        assertTrue(ledger.hasChiSanBi(Seat.RIGHT, Seat.TOP))
        // 换来源不累计
        assertFalse(ledger.hasChiSanBi(Seat.RIGHT, Seat.LEFT))
    }

    @Test
    fun `暗杠与补杠不计入吃三比`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.ANKAN, Seat.RIGHT, Tile.manzu(1))
        ledger.record(LedgerEventType.SHOUMINKAN, Seat.RIGHT, Tile.manzu(3))
        ledger.record(LedgerEventType.CHI, Seat.RIGHT, Tile.manzu(5), fromSeat = Seat.TOP)
        // 只有 1 次吃来自对家
        assertEquals(1, ledger.meldCountFromSameSource(Seat.RIGHT, Seat.TOP))
        assertFalse(ledger.hasChiSanBi(Seat.RIGHT, Seat.TOP))
    }

    @Test
    fun `杠事件台账`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.MINKAN, Seat.SELF, Tile.manzu(1), fromSeat = Seat.TOP)
        ledger.record(LedgerEventType.ANKAN, Seat.LEFT, Tile.manzu(3))
        ledger.record(LedgerEventType.DISCARD, Seat.SELF, Tile.manzu(5))
        val kans = ledger.kanEvents()
        assertEquals(2, kans.size)
        assertTrue(kans.any { it.type == LedgerEventType.MINKAN })
        assertTrue(kans.any { it.type == LedgerEventType.ANKAN })
    }

    @Test
    fun `最后一张判定可靠与不可靠`() {
        // 可靠：自摸紧邻和牌 → true
        val ok = ActionLedger()
        ok.record(LedgerEventType.DISCARD, Seat.TOP, Tile.manzu(1))
        ok.record(LedgerEventType.DRAW, Seat.SELF, Tile.manzu(9))
        ok.record(LedgerEventType.WIN, Seat.SELF, Tile.manzu(9))
        assertEquals(true, ok.isLastTileWinReliable())

        // 点炮：和牌紧邻他家弃牌 → false（非海底）
        val ron = ActionLedger()
        ron.record(LedgerEventType.DISCARD, Seat.TOP, Tile.manzu(1))
        ron.record(LedgerEventType.WIN, Seat.SELF, Tile.manzu(1))
        assertEquals(false, ron.isLastTileWinReliable())

        // 摸牌与和牌不相邻（中间有他家弃牌）→ false（非自摸海底）
        val gap = ActionLedger()
        gap.record(LedgerEventType.DRAW, Seat.SELF, Tile.manzu(9))
        gap.record(LedgerEventType.DISCARD, Seat.LEFT, Tile.manzu(2))
        gap.record(LedgerEventType.WIN, Seat.SELF, Tile.manzu(9))
        assertEquals(false, gap.isLastTileWinReliable())

        // 信息不足：仅有和牌事件 → null
        val noInfo = ActionLedger()
        noInfo.record(LedgerEventType.WIN, Seat.SELF, Tile.manzu(9))
        assertNull(noInfo.isLastTileWinReliable())
    }

    @Test
    fun `重置清空账本`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.DISCARD, Seat.TOP, Tile.manzu(1))
        ledger.reset()
        assertTrue(ledger.events().isEmpty())
        assertNull(ledger.lastDraw())
        assertEquals(0, ledger.meldCountFromSameSource(Seat.RIGHT, Seat.TOP))
    }
}
