package com.weile.liuzhou.mahjong.domain.vision

import com.weile.liuzhou.mahjong.domain.game.GamePhase
import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别 → 帧观测适配器测试（P4 整合层，纯 JVM）。
 */
class RecognitionAdapterTest {

    @Test
    fun `阶段映射`() {
        fun result(state: ScreenState) = RecognitionResult(
            viewport = ViewportResult(0, 0, 100, 100, 100, 100),
            screenState = state,
            tiles = emptyList(),
            unknownRatio = 0f,
            templateLibraryReady = false,
        )
        assertEquals(GamePhase.PLAYING, result(ScreenState.PLAYING).toFrameObservation().phase)
        assertEquals(GamePhase.OWN_TURN, result(ScreenState.OWN_TURN).toFrameObservation().phase)
        assertEquals(GamePhase.ANIMATION, result(ScreenState.ANIMATION).toFrameObservation().phase)
        assertEquals(GamePhase.SETTLEMENT, result(ScreenState.SETTLEMENT).toFrameObservation().phase)
        assertEquals(GamePhase.PREPARING, result(ScreenState.PREPARING).toFrameObservation().phase)
        assertEquals(GamePhase.UNKNOWN, result(ScreenState.UNKNOWN).toFrameObservation().phase)
    }

    @Test
    fun `手牌摸牌与弃牌区映射`() {
        val result = RecognitionResult(
            viewport = ViewportResult(0, 0, 100, 100, 100, 100),
            screenState = ScreenState.OWN_TURN,
            tiles = listOf(
                TileMatch(Tile.manzu(1), 0.99f, TileRegion(0, 0, 10, 10), TileSource.SELF_HAND),
                TileMatch(null, 0f, TileRegion(10, 0, 20, 10), TileSource.SELF_HAND),
                TileMatch(Tile.pinzu(5), 0.95f, TileRegion(20, 0, 30, 10), TileSource.SELF_DRAWN),
                TileMatch(Tile.souzu(3), 0.9f, TileRegion(0, 10, 10, 20), TileSource.DISCARD_TOP),
                TileMatch(Tile.wind(0), 0.9f, TileRegion(10, 10, 20, 20), TileSource.DISCARD_LEFT),
            ),
            unknownRatio = 1f / 5f,
            templateLibraryReady = false,
        )
        val frame = result.toFrameObservation()

        assertEquals(GamePhase.OWN_TURN, frame.phase)
        // 手牌保留 null（未识别）
        assertEquals(listOf(Tile.manzu(1), null), frame.ownHand)
        assertEquals(Tile.pinzu(5), frame.ownDrawn)
        // 弃牌区按座位归位
        assertEquals(listOf(Tile.souzu(3)), frame.discardPiles[Seat.TOP])
        assertEquals(listOf(Tile.wind(0)), frame.discardPiles[Seat.LEFT])
        assertFalse(frame.discardPiles.containsKey(Seat.SELF))
        assertFalse(frame.discardPiles.containsKey(Seat.RIGHT))
        // 未知牌率透传
        assertEquals(1f / 5f, frame.unknownRatio, 1e-6f)
        // P1 无副露识别
        assertTrue(frame.openMelds.isEmpty())
    }

    @Test
    fun `弃牌区中的未知牌被过滤`() {
        val result = RecognitionResult(
            viewport = ViewportResult(0, 0, 100, 100, 100, 100),
            screenState = ScreenState.PLAYING,
            tiles = listOf(
                TileMatch(null, 0f, TileRegion(0, 0, 10, 10), TileSource.DISCARD_SELF),
            ),
            unknownRatio = 1f,
            templateLibraryReady = false,
        )
        val frame = result.toFrameObservation()
        // 全未知的弃牌区不建立空映射
        assertFalse(frame.discardPiles.containsKey(Seat.SELF))
    }

    @Test
    fun `未知阶段帧被状态机冻结`() {
        // 端到端小验证：UNKNOWN 帧 → 状态机冻结（不接住具体异常即可）
        val result = RecognitionResult(
            viewport = ViewportResult(0, 0, 100, 100, 100, 100),
            screenState = ScreenState.UNKNOWN,
            tiles = emptyList(),
            unknownRatio = 1f,
            templateLibraryReady = false,
        )
        val frame = result.toFrameObservation()
        assertEquals(GamePhase.UNKNOWN, frame.phase)
        assertNull(frame.ownHand.firstOrNull())
    }
}
