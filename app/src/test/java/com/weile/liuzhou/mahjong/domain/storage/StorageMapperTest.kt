package com.weile.liuzhou.mahjong.domain.storage

import com.weile.liuzhou.mahjong.domain.analysis.AnalysisMode
import com.weile.liuzhou.mahjong.domain.game.GameEvent
import com.weile.liuzhou.mahjong.domain.game.GamePhase
import com.weile.liuzhou.mahjong.domain.game.MeldType
import com.weile.liuzhou.mahjong.domain.game.OpenMeld
import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储映射测试（纯 JVM）：事件流 ↔ 展平结构、模式/座位名往返。
 */
class StorageMapperTest {

    @Test
    fun `摸牌事件往返`() {
        val event = GameEvent.Drew(Tile.manzu(5))
        val stored = event.toStored(0)
        assertEquals("DREW", stored.type)
        assertEquals(Tile.manzu(5).code, stored.tileCode)
        assertEquals(event, stored.toGameEvent())
    }

    @Test
    fun `出牌事件往返`() {
        val event = GameEvent.Discarded(Seat.TOP, Tile.pinzu(9))
        val stored = event.toStored(3)
        assertEquals("DISCARD", stored.type)
        assertEquals("TOP", stored.seat)
        assertEquals(event, stored.toGameEvent())
    }

    @Test
    fun `副露事件往返保留来源与类型`() {
        val meld = OpenMeld(MeldType.PON, List(3) { Tile.souzu(7) }, fromSeat = Seat.LEFT)
        val event = GameEvent.MeldDeclared(Seat.RIGHT, meld, Seat.LEFT)
        val stored = event.toStored(7)
        assertEquals("MELD", stored.type)
        assertEquals("LEFT", stored.fromSeat)
        assertEquals("PON", stored.extra)

        val restored = stored.toGameEvent() as GameEvent.MeldDeclared
        assertEquals(Seat.RIGHT, restored.seat)
        assertEquals(Seat.LEFT, restored.sourceSeat)
        assertEquals(Tile.souzu(7), restored.meld.tiles.first())
    }

    @Test
    fun `阶段与重置事件往返`() {
        val phase = GameEvent.PhaseChanged(GamePhase.PLAYING, GamePhase.OWN_TURN).toStored(1)
        assertEquals("PHASE", phase.type)
        assertEquals("PLAYING->OWN_TURN", phase.extra)
        val restoredPhase = phase.toGameEvent() as GameEvent.PhaseChanged
        assertEquals(GamePhase.PLAYING, restoredPhase.from)
        assertEquals(GamePhase.OWN_TURN, restoredPhase.to)

        val reset = GameEvent.RoundReset("auto: SETTLEMENT").toStored(2)
        assertEquals("RESET", reset.type)
        assertEquals(GameEvent.RoundReset("auto: SETTLEMENT"), reset.toGameEvent())
    }

    @Test
    fun `未知类型与越界牌码返回null`() {
        assertNull(StoredEvent(0, "MYSTERY", "SELF", null, null, null).toGameEvent())
        assertNull(StoredEvent(0, "DISCARD", "NOSUCHSEAT", null, null, null).toGameEvent())
        assertNull(StoredEvent(0, "DREW", "SELF", 999, null, null).toGameEvent())
    }

    @Test
    fun `事件流批量转换保序`() {
        val events = listOf(
            GameEvent.Drew(Tile.manzu(1)),
            GameEvent.Discarded(Seat.SELF, Tile.manzu(1)),
            GameEvent.PhaseChanged(GamePhase.PLAYING, GamePhase.SETTLEMENT),
            GameEvent.RoundReset("auto: SETTLEMENT"),
        )
        val stored = events.toStoredList()
        assertEquals(listOf(0, 1, 2, 3), stored.map { it.seq })
        val restored = stored.toGameEvents()
        assertEquals(events, restored)
    }

    @Test
    fun `模式名往返与回退`() {
        AnalysisMode.entries.forEach { mode ->
            assertEquals(mode, modeFromName(modeName(mode)))
        }
        assertEquals(AnalysisMode.BALANCED, modeFromName(null))
        assertEquals(AnalysisMode.BALANCED, modeFromName("GARBAGE"))
    }

    @Test
    fun `座位名往返`() {
        Seat.entries.forEach { seat ->
            assertEquals(seat, seatFromName(seatName(seat)))
        }
        assertNull(seatFromName("NOWHERE"))
    }
}
